package com.alimentared.backend.service.impl;

import com.alimentared.backend.config.AlimentaRedProperties;
import com.alimentared.backend.dto.request.ApelacionStrikeRequest;
import com.alimentared.backend.dto.response.StrikeResponse;
import com.alimentared.backend.entity.Asignacion;
import com.alimentared.backend.entity.Organizacion;
import com.alimentared.backend.entity.Solicitud;
import com.alimentared.backend.entity.Strike;
import com.alimentared.backend.entity.Usuario;
import com.alimentared.backend.entity.enums.EntidadAuditada;
import com.alimentared.backend.entity.enums.EstadoSolicitud;
import com.alimentared.backend.exception.BusinessRuleException;
import com.alimentared.backend.exception.ForbiddenOperationException;
import com.alimentared.backend.exception.ResourceNotFoundException;
import com.alimentared.backend.mapper.OrganizacionMapper;
import com.alimentared.backend.repository.OrganizacionRepository;
import com.alimentared.backend.repository.SolicitudRepository;
import com.alimentared.backend.repository.StrikeRepository;
import com.alimentared.backend.service.AuditoriaService;
import com.alimentared.backend.service.StrikeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrikeServiceImpl implements StrikeService {

    private static final String ACTIVA = "ACTIVA";
    private static final String BLOQUEADA = "BLOQUEADA";

    private final StrikeRepository strikeRepository;
    private final OrganizacionRepository organizacionRepository;
    private final SolicitudRepository solicitudRepository;
    private final AuditoriaService auditoriaService;
    private final OrganizacionMapper organizacionMapper;
    private final AccesoHelper accesoHelper;
    private final AlimentaRedProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public Strike registrarStrike(Organizacion organizacion, Asignacion asignacion, String nombre,
                                  String detalles, Usuario responsable) {
        if (organizacion == null) {
            throw new BusinessRuleException("RN08", "Los strikes aplican únicamente a organizaciones Donantes o Receptoras. Los administradores y coordinadores no pueden recibir strikes.");
        }
        Strike strike = strikeRepository.save(Strike.builder()
                .organizacion(organizacion)
                .asignacion(asignacion)
                .nombreStrike(nombre)
                .detalles(detalles)
                .fechaRegistro(LocalDateTime.now(clock))
                .build());
        organizacion.setStrikesVigentes(organizacion.getStrikesVigentes() + 1);
        auditoriaService.registrar(EntidadAuditada.STRIKE, strike.getIdStrike(), null, "VIGENTE", responsable, nombre);

        int maximo = properties.reglas().maxStrikes();
        if (!organizacion.isBloqueada() && organizacion.getStrikesVigentes() >= maximo) {
            bloquearCuenta(organizacion, responsable, maximo);
        }
        return strike;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrikeResponse> consultarStrikes(Long organizacionId, Long usuarioId) {
        Usuario usuario = accesoHelper.usuario(usuarioId);
        Organizacion organizacion = buscarOrganizacion(organizacionId);
        if (!accesoHelper.esCoordinacion(usuario) && !accesoHelper.perteneceA(usuario, organizacion)) {
            throw new ForbiddenOperationException("No tiene acceso a los strikes de esta organización");
        }
        return strikeRepository.findByOrganizacion_IdOrganizacionOrderByFechaRegistroDesc(organizacionId).stream()
                .map(organizacionMapper::toStrikeResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrikeResponse> listarTodosStrikes(Long usuarioId) {
        Usuario usuario = accesoHelper.usuario(usuarioId);
        if (!accesoHelper.esCoordinacion(usuario)) {
            throw new ForbiddenOperationException("Solo la coordinación puede consultar todos los strikes");
        }
        return strikeRepository.findAllByOrderByFechaRegistroDesc().stream()
                .map(organizacionMapper::toStrikeResponse)
                .toList();
    }

    @Override
    @Transactional
    public StrikeResponse apelarStrike(Long organizacionId, ApelacionStrikeRequest request, Long usuarioId) {
        Usuario evaluador = accesoHelper.usuario(usuarioId);
        if (!accesoHelper.esCoordinacion(evaluador)) {
            throw new ForbiddenOperationException("Solo la coordinación puede evaluar apelaciones de strikes");
        }
        Organizacion organizacion = buscarOrganizacion(organizacionId);
        Strike strike = strikeRepository.findById(request.strikeId())
                .orElseThrow(() -> new ResourceNotFoundException("Strike", request.strikeId()));
        if (!Objects.equals(strike.getOrganizacion().getIdOrganizacion(), organizacionId)) {
            throw new BusinessRuleException("RN12", "El strike no pertenece a la organización indicada");
        }
        if (!strike.isVigente() || strike.getFechaApelacion() != null) {
            throw new BusinessRuleException("RN12", "El strike ya fue evaluado");
        }

        strike.setJustificacionApelacion(request.justificacion().trim());
        strike.setFechaApelacion(LocalDateTime.now(clock));
        strike.setUsuarioEvaluador(evaluador);

        if (Boolean.TRUE.equals(request.aprobada())) {
            strike.setVigente(false);
            organizacion.setStrikesVigentes(Math.max(0, organizacion.getStrikesVigentes() - 1));
            auditoriaService.registrar(EntidadAuditada.STRIKE, strike.getIdStrike(), "VIGENTE", "ANULADO",
                    evaluador, "Apelación aprobada: " + request.justificacion());
            if (organizacion.isBloqueada() && organizacion.getStrikesVigentes() < properties.reglas().maxStrikes()) {
                organizacion.setBloqueada(false);
                auditoriaService.registrar(EntidadAuditada.ORGANIZACION, organizacionId, BLOQUEADA, ACTIVA,
                    evaluador, "Cuenta reactivada tras apelación de strike");
            }
        } else {
            auditoriaService.registrar(EntidadAuditada.STRIKE, strike.getIdStrike(), "VIGENTE",
                    "APELACION_RECHAZADA", evaluador, "Apelación rechazada: " + request.justificacion());
        }
        return organizacionMapper.toStrikeResponse(strike);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verificarBloqueoCuenta(Long organizacionId) {
        return buscarOrganizacion(organizacionId).isBloqueada();
    }

    /** RN08: suspende la cuenta y cancela sus solicitudes pendientes para liberar los lotes. */
    private void bloquearCuenta(Organizacion organizacion, Usuario responsable, int maximo) {
        organizacion.setBloqueada(true);
        auditoriaService.registrar(EntidadAuditada.ORGANIZACION, organizacion.getIdOrganizacion(), ACTIVA,
                BLOQUEADA, responsable, "RN08: acumulación de %d strikes".formatted(maximo));

        LocalDateTime ahora = LocalDateTime.now(clock);
        List<Solicitud> pendientes = solicitudRepository.findByOrganizacion_IdOrganizacionAndEstado(
                organizacion.getIdOrganizacion(), EstadoSolicitud.PENDIENTE);
        for (Solicitud solicitud : pendientes) {
            solicitud.setEstado(EstadoSolicitud.CANCELADO);
            solicitud.setFechaResolucion(ahora);
            solicitud.setEnEmpate(false);
            solicitud.setMotivoResolucion("Cuenta suspendida por acumulación de strikes");
            auditoriaService.registrar(EntidadAuditada.SOLICITUD, solicitud.getIdSolicitud(),
                    EstadoSolicitud.PENDIENTE, EstadoSolicitud.CANCELADO, responsable, "Cuenta suspendida (RN08)");
        }
        log.info("Organización {} suspendida por strikes", organizacion.getIdOrganizacion());
    }

    private Organizacion buscarOrganizacion(Long organizacionId) {
        return organizacionRepository.findById(organizacionId)
                .orElseThrow(() -> new ResourceNotFoundException("Organización", organizacionId));
    }
}
