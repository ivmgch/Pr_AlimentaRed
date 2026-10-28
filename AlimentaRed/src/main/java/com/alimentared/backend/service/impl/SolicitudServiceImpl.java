package com.alimentared.backend.service.impl;

import com.alimentared.backend.config.AlimentaRedProperties;
import com.alimentared.backend.dto.request.CancelacionRequest;
import com.alimentared.backend.dto.request.ResolverSolicitudRequest;
import com.alimentared.backend.dto.request.SolicitudRequest;
import com.alimentared.backend.dto.response.HistorialEstadoResponse;
import com.alimentared.backend.dto.response.PageResponse;
import com.alimentared.backend.dto.response.SolicitudResponse;
import com.alimentared.backend.entity.Asignacion;
import com.alimentared.backend.entity.Lote;
import com.alimentared.backend.entity.Organizacion;
import com.alimentared.backend.entity.Solicitud;
import com.alimentared.backend.entity.Usuario;
import com.alimentared.backend.entity.enums.EntidadAuditada;
import com.alimentared.backend.entity.enums.EstadoAsignacion;
import com.alimentared.backend.entity.enums.EstadoSolicitud;
import com.alimentared.backend.entity.enums.Rol;
import com.alimentared.backend.exception.BusinessRuleException;
import com.alimentared.backend.exception.ForbiddenOperationException;
import com.alimentared.backend.exception.ResourceNotFoundException;
import com.alimentared.backend.mapper.SolicitudMapper;
import com.alimentared.backend.repository.AsignacionRepository;
import com.alimentared.backend.repository.LoteRepository;
import com.alimentared.backend.repository.OrganizacionRepository;
import com.alimentared.backend.repository.SolicitudRepository;
import com.alimentared.backend.service.AuditoriaService;
import com.alimentared.backend.service.InventarioService;
import com.alimentared.backend.service.SolicitudService;
import com.alimentared.backend.service.StrikeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SolicitudServiceImpl implements SolicitudService {

    private static final String PENDIENTE_DECISION_DONANTE = "PENDIENTE_DECISION_DONANTE";

    private final SolicitudRepository solicitudRepository;
    private final AsignacionRepository asignacionRepository;
    private final LoteRepository loteRepository;
    private final OrganizacionRepository organizacionRepository;
    private final InventarioService inventarioService;
    private final StrikeService strikeService;
    private final AuditoriaService auditoriaService;
    private final AccesoHelper accesoHelper;
    private final SolicitudMapper solicitudMapper;
    private final AlimentaRedProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    @Transactional
    public SolicitudResponse crearSolicitud(SolicitudRequest request, Long usuarioId) {
        Usuario usuario = accesoHelper.usuario(usuarioId);
        accesoHelper.validarRol(usuario, Rol.RECEPTORA);
        Organizacion organizacion = accesoHelper.organizacionDe(usuario);
        accesoHelper.validarOrganizacionOperativa(organizacion);

        // Bloquea la organización para que dos peticiones simultáneas no superen el límite RN07.
        organizacionRepository.findByIdForUpdate(organizacion.getIdOrganizacion());
        long activas = solicitudRepository.countByOrganizacion_IdOrganizacionAndEstadoIn(
                organizacion.getIdOrganizacion(), EstadoSolicitud.ACTIVOS);
        int maximo = properties.reglas().maxSolicitudesActivas();
        if (activas >= maximo) {
            throw new BusinessRuleException("RN07",
                    "La organización alcanzó el límite de %d solicitudes activas simultáneas".formatted(maximo));
        }

        Lote lote = loteRepository.findById(request.loteId())
                .orElseThrow(() -> new ResourceNotFoundException("Lote", request.loteId()));
        LocalDateTime ahora = LocalDateTime.now(clock);
        inventarioService.validarLoteSolicitable(lote, ahora);
        if (solicitudRepository.existsByLote_IdLoteAndOrganizacion_IdOrganizacionAndEstadoIn(
                lote.getIdLote(), organizacion.getIdOrganizacion(), EstadoSolicitud.ACTIVOS)) {
            throw new BusinessRuleException("RF05", "La organización ya tiene una solicitud activa sobre este lote");
        }
        inventarioService.validarFraccionamiento(lote, request.cantidadSolicitada());

        Solicitud solicitud = solicitudRepository.save(
                solicitudMapper.toEntity(lote, organizacion, request.cantidadSolicitada(), ahora));
        auditoriaService.registrar(EntidadAuditada.SOLICITUD, solicitud.getIdSolicitud(), null,
                EstadoSolicitud.PENDIENTE, usuario, "Solicitud creada por " + organizacion.getNombre());
        return solicitudMapper.toResponse(solicitud);
    }

    @Override
    @Transactional
    public SolicitudResponse resolverSolicitud(Long solicitudId, ResolverSolicitudRequest request, Long usuarioId) {
        Usuario usuario = accesoHelper.usuario(usuarioId);
        Solicitud solicitud = buscar(solicitudId);
        Lote lote = inventarioService.bloquearLote(solicitud.getLote().getIdLote());
        accesoHelper.validarResponsableDelLote(usuario, lote);
        validarPendiente(solicitud);

        if (Boolean.TRUE.equals(request.aprobada())) {
            String motivo = textoODefecto(request.motivo(), "Aprobada por " + usuario.getNombre());
            aprobar(solicitud, lote, usuario, false, motivo);
        } else {
            actualizarEstado(solicitud, EstadoSolicitud.RECHAZADA, usuario, request.motivo().trim());
        }
        return solicitudMapper.toResponse(solicitud);
    }

    @Override
    @Transactional
    public SolicitudResponse cancelarSolicitud(Long solicitudId, CancelacionRequest request, Long usuarioId) {
        Usuario usuario = accesoHelper.usuario(usuarioId);
        Solicitud solicitud = buscar(solicitudId);
        boolean coordinacion = accesoHelper.esCoordinacion(usuario);
        if (!coordinacion && !accesoHelper.perteneceA(usuario, solicitud.getOrganizacion())) {
            throw new ForbiddenOperationException("Solo la organización solicitante o la coordinación pueden cancelarla");
        }

        String motivo = request.motivo().trim();
        switch (solicitud.getEstado()) {
            case PENDIENTE -> actualizarEstado(solicitud, EstadoSolicitud.CANCELADO, usuario, motivo);
            case APROBADO -> cancelarSolicitudAsignada(solicitud, usuario, motivo, coordinacion);
            default -> throw new BusinessRuleException("RF08",
                    "No se puede cancelar una solicitud en estado " + solicitud.getEstado());
        }
        return solicitudMapper.toResponse(solicitud);
    }

    @Override
    @Transactional(readOnly = true)
    public SolicitudResponse obtenerSolicitud(Long solicitudId, Long usuarioId) {
        Usuario usuario = accesoHelper.usuario(usuarioId);
        Solicitud solicitud = buscar(solicitudId);
        validarVisibilidad(usuario, solicitud);
        return solicitudMapper.toResponse(solicitud);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SolicitudResponse> obtenerSolicitudesPorOrganizacion(Long usuarioId, Pageable pageable) {
        Organizacion organizacion = accesoHelper.organizacionDe(accesoHelper.usuario(usuarioId));
        Page<Solicitud> page = solicitudRepository.findByOrganizacion_IdOrganizacion(
                organizacion.getIdOrganizacion(), pageable);
        return PageResponse.from(page.map(solicitudMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SolicitudResponse> listarPorLote(Long loteId, Long usuarioId, Pageable pageable) {
        Usuario usuario = accesoHelper.usuario(usuarioId);
        Lote lote = loteRepository.findById(loteId)
                .orElseThrow(() -> new ResourceNotFoundException("Lote", loteId));
        accesoHelper.validarResponsableDelLote(usuario, lote);
        return PageResponse.from(solicitudRepository.findByLote_IdLote(loteId, pageable).map(solicitudMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SolicitudResponse> listarSolicitudes(EstadoSolicitud estado, Pageable pageable) {
        Page<Solicitud> page = estado == null
                ? solicitudRepository.findAllBy(pageable)
                : solicitudRepository.findByEstado(estado, pageable);
        return PageResponse.from(page.map(solicitudMapper::toResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public long contarSolicitudesActivas(Long usuarioId) {
        Organizacion organizacion = accesoHelper.organizacionDe(accesoHelper.usuario(usuarioId));
        return solicitudRepository.countByOrganizacion_IdOrganizacionAndEstadoIn(
                organizacion.getIdOrganizacion(), EstadoSolicitud.ACTIVOS);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistorialEstadoResponse> obtenerHistorial(Long solicitudId, Long usuarioId) {
        Usuario usuario = accesoHelper.usuario(usuarioId);
        validarVisibilidad(usuario, buscar(solicitudId));
        return auditoriaService.listar(EntidadAuditada.SOLICITUD, solicitudId);
    }

    @Override
    @Transactional
    public void actualizarEstado(Solicitud solicitud, EstadoSolicitud nuevoEstado, Usuario responsable, String motivo) {
        EstadoSolicitud anterior = solicitud.getEstado();
        solicitud.setEstado(nuevoEstado);
        solicitud.setEnEmpate(false);
        solicitud.setMotivoResolucion(motivo);
        if (anterior == EstadoSolicitud.PENDIENTE) {
            // Solo la primera resolución mide el tiempo de respuesta del donante (RF09).
            solicitud.setFechaResolucion(LocalDateTime.now(clock));
        }
        auditoriaService.registrar(EntidadAuditada.SOLICITUD, solicitud.getIdSolicitud(), anterior, nuevoEstado,
                responsable, motivo);
    }

    @Override
    @Transactional
    public void cerrarSolicitudesPendientes(Long loteId, EstadoSolicitud estadoFinal, Usuario responsable, String motivo) {
        solicitudRepository.findByLote_IdLoteAndEstadoOrderByFechaCreacionAsc(loteId, EstadoSolicitud.PENDIENTE)
                .forEach(solicitud -> actualizarEstado(solicitud, estadoFinal, responsable, motivo));
    }

    /**
     * RN09: la auto-asignación por orden de llegada espera una ventana de 60 s. Si en esa ventana
     * llegó más de una solicitud al mismo lote, todas quedan pendientes de decisión del donante.
     * Cada lote se procesa en su propia transacción para aislar fallos.
     */
    @Override
    @Scheduled(cron = "${alimentared.cron.empate:*/10 * * * * *}")
    public void procesarVentanaEmpate() {
        LocalDateTime ahora = LocalDateTime.now(clock);
        LocalDateTime limite = ahora.minusSeconds(properties.reglas().ventanaEmpateSegundos());
        List<Long> lotes = solicitudRepository.findLotesConVentanaCerrada(EstadoSolicitud.PENDIENTE, limite);
        for (Long loteId : lotes) {
            try {
                transactionTemplate.executeWithoutResult(status -> procesarEmpateDelLote(loteId, ahora));
            } catch (RuntimeException ex) {
                log.warn("No se pudo procesar la ventana de empate del lote {}: {}", loteId, ex.getMessage());
            }
        }
    }

    /** RN06: el donante tiene 1 hora para confirmar; después la solicitud expira y libera el lote. */
    @Override
    @Transactional
    @Scheduled(cron = "${alimentared.cron.sla:0 * * * * *}")
    public void verificarSlaExpiracion() {
        LocalDateTime limite = LocalDateTime.now(clock).minusMinutes(properties.reglas().slaConfirmacionMinutos());
        List<Solicitud> vencidas = solicitudRepository.findByEstadoAndFechaCreacionBefore(EstadoSolicitud.PENDIENTE, limite);
        vencidas.forEach(solicitud -> actualizarEstado(solicitud, EstadoSolicitud.EXPIRADA, null,
                "RN06: SLA de confirmación superado sin respuesta del donante"));
        if (!vencidas.isEmpty()) {
            log.info("{} solicitudes expiradas por SLA", vencidas.size());
        }
    }

    private void procesarEmpateDelLote(Long loteId, LocalDateTime ahora) {
        Lote lote = inventarioService.bloquearLote(loteId);
        List<Solicitud> pendientes = solicitudRepository.findByLote_IdLoteAndEstadoOrderByFechaCreacionAsc(
                loteId, EstadoSolicitud.PENDIENTE);
        if (pendientes.isEmpty()) {
            return;
        }
        long ventana = properties.reglas().ventanaEmpateSegundos();

        if (pendientes.stream().anyMatch(Solicitud::isEnEmpate)) {
            // Ya hay una decisión pendiente del donante: las nuevas solicitudes se suman a ella.
            pendientes.stream()
                    .filter(solicitud -> !solicitud.isEnEmpate())
                    .filter(solicitud -> !solicitud.getFechaCreacion().plusSeconds(ventana).isAfter(ahora))
                    .forEach(this::marcarEmpate);
            return;
        }

        Solicitud primera = pendientes.getFirst();
        LocalDateTime cierreVentana = primera.getFechaCreacion().plusSeconds(ventana);
        if (cierreVentana.isAfter(ahora)) {
            return;
        }
        List<Solicitud> enVentana = pendientes.stream()
                .filter(solicitud -> !solicitud.getFechaCreacion().isAfter(cierreVentana))
                .toList();
        if (enVentana.size() > 1) {
            enVentana.forEach(this::marcarEmpate);
            return;
        }
        try {
            aprobar(primera, lote, null, true, "Auto-asignación por orden de llegada (sin competencia en la ventana)");
        } catch (BusinessRuleException ex) {
            actualizarEstado(primera, EstadoSolicitud.RECHAZADA, null, "Auto-asignación no posible: " + ex.getMessage());
        }
    }

    /**
     * Valida de nuevo en el momento exacto de la asignación (RN01, RN02, RN10), descuenta
     * inventario (RN03) y revela los datos de recogida al crear la asignación (RF06).
     */
    private void aprobar(Solicitud solicitud, Lote lote, Usuario responsable, boolean automatica, String motivo) {
        LocalDateTime ahora = LocalDateTime.now(clock);
        if (solicitud.getOrganizacion().isBloqueada()) {
            throw new BusinessRuleException("RN08", "La organización receptora está suspendida por strikes");
        }
        BigDecimal cantidad = solicitud.getCantidadSolicitada();
        inventarioService.validarLoteSolicitable(lote, ahora);
        inventarioService.validarFraccionamiento(lote, cantidad);
        inventarioService.descontar(lote, cantidad, responsable);

        actualizarEstado(solicitud, EstadoSolicitud.APROBADO, responsable, motivo);
        Asignacion asignacion = asignacionRepository.save(Asignacion.builder()
                .solicitud(solicitud)
                .cantidadAsignada(cantidad)
                .estado(EstadoAsignacion.ACTIVA)
                .autoAsignada(automatica)
                .fechaAsignacion(ahora)
                .fechaLimiteRetiro(ahora.plusHours(properties.reglas().horasLimiteRetiro()))
                .usuarioResponsable(responsable)
                .build());
        auditoriaService.registrar(EntidadAuditada.ASIGNACION, asignacion.getIdAsignacion(), null,
                EstadoAsignacion.ACTIVA, responsable, automatica ? "Auto-asignación FCFS" : "Asignación aprobada");

        if (lote.getCantidadDisponible().signum() == 0) {
            solicitudRepository.findByLote_IdLoteAndEstadoOrderByFechaCreacionAsc(lote.getIdLote(), EstadoSolicitud.PENDIENTE)
                    .stream()
                    .filter(otra -> !otra.getIdSolicitud().equals(solicitud.getIdSolicitud()))
                    .forEach(otra -> actualizarEstado(otra, EstadoSolicitud.RECHAZADA, responsable,
                            "El inventario del lote se agotó: empate perdido"));
        }
    }

    /** Cancelar una solicitud ya asignada repone el inventario; si la cancela la receptora suma un strike (RN12). */
    private void cancelarSolicitudAsignada(Solicitud solicitud, Usuario usuario, String motivo, boolean coordinacion) {
        Lote lote = inventarioService.bloquearLote(solicitud.getLote().getIdLote());
        Asignacion asignacion = asignacionRepository.findBySolicitud_IdSolicitud(solicitud.getIdSolicitud())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "La solicitud %d no tiene asignación".formatted(solicitud.getIdSolicitud())));
        if (!EstadoAsignacion.ABIERTOS.contains(asignacion.getEstado())) {
            throw new BusinessRuleException("RF08", "La asignación de esta solicitud ya fue cerrada");
        }

        inventarioService.reponer(lote, asignacion.getCantidadPendiente(), usuario,
                "Restock por cancelación de la solicitud #" + solicitud.getIdSolicitud());
        EstadoAsignacion anterior = asignacion.getEstado();
        asignacion.setEstado(EstadoAsignacion.CANCELADA);
        asignacion.setFechaCierre(LocalDateTime.now(clock));
        auditoriaService.registrar(EntidadAuditada.ASIGNACION, asignacion.getIdAsignacion(), anterior,
                EstadoAsignacion.CANCELADA, usuario, motivo);
        actualizarEstado(solicitud, EstadoSolicitud.CANCELADO, usuario, motivo);

        if (!coordinacion) {
            strikeService.registrarStrike(solicitud.getOrganizacion(), asignacion, "Cancelación injustificada",
                    "Cancelación de una solicitud ya asignada: " + motivo, usuario);
        }
    }

    private void marcarEmpate(Solicitud solicitud) {
        solicitud.setEnEmpate(true);
        auditoriaService.registrar(EntidadAuditada.SOLICITUD, solicitud.getIdSolicitud(), EstadoSolicitud.PENDIENTE,
                PENDIENTE_DECISION_DONANTE, null,
                "Varias solicitudes en la misma ventana; el donante decide la asignación");
    }

    private void validarPendiente(Solicitud solicitud) {
        if (solicitud.getEstado() != EstadoSolicitud.PENDIENTE) {
            throw new BusinessRuleException("RF06",
                    "Solo se pueden resolver solicitudes pendientes (estado actual: %s)".formatted(solicitud.getEstado()));
        }
    }

    private void validarVisibilidad(Usuario usuario, Solicitud solicitud) {
        boolean permitido = accesoHelper.esCoordinacion(usuario)
                || accesoHelper.perteneceA(usuario, solicitud.getOrganizacion())
                || accesoHelper.esDonanteResponsable(usuario, solicitud.getLote());
        if (!permitido) {
            throw new ForbiddenOperationException("No tiene acceso a esta solicitud");
        }
    }

    private Solicitud buscar(Long solicitudId) {
        return solicitudRepository.findById(solicitudId)
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud", solicitudId));
    }

    private static String textoODefecto(String texto, String defecto) {
        return texto == null || texto.isBlank() ? defecto : texto.trim();
    }
}
