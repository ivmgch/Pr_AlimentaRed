package com.alimentared.backend.config;

import com.alimentared.backend.entity.Lote;
import com.alimentared.backend.entity.Organizacion;
import com.alimentared.backend.entity.Strike;
import com.alimentared.backend.entity.TipoAlimento;
import com.alimentared.backend.entity.Usuario;
import com.alimentared.backend.entity.enums.EstadoAlimento;
import com.alimentared.backend.entity.enums.EstadoLote;
import com.alimentared.backend.entity.enums.Rol;
import com.alimentared.backend.repository.LoteRepository;
import com.alimentared.backend.repository.OrganizacionRepository;
import com.alimentared.backend.repository.StrikeRepository;
import com.alimentared.backend.repository.TipoAlimentoRepository;
import com.alimentared.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** Crea los usuarios internos, organizaciones, lotes y categoras base cuando no existen. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "alimentared.seed", name = "habilitado", havingValue = "true")
public class DataInitializer implements CommandLineRunner {

    private final UsuarioRepository usuarioRepository;
    private final OrganizacionRepository organizacionRepository;
    private final TipoAlimentoRepository tipoAlimentoRepository;
    private final LoteRepository loteRepository;
    private final StrikeRepository strikeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AlimentaRedProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public void run(String... args) {
        AlimentaRedProperties.Seed seed = properties.seed();
        crearUsuarioSiNoExiste(Rol.ADMINISTRADOR, "Administrador AlimentaRed",
                seed.adminCorreo(), seed.adminContrasena(), null);
        crearUsuarioSiNoExiste(Rol.COORDINADOR, "Coordinador Red Comunitaria",
                seed.coordinadorCorreo(), seed.coordinadorContrasena(), null);
        crearCategoriasBase();
        crearOrganizacionesYUsuarios();
        crearLotesDePrueba();
    }

    private Usuario crearUsuarioSiNoExiste(Rol rol, String nombre, String correo, String contrasena, Organizacion org) {
        String correoNorm = correo.trim().toLowerCase();
        return usuarioRepository.findByCorreoIgnoreCase(correoNorm)
                .orElseGet(() -> {
                    Usuario u = usuarioRepository.save(Usuario.builder()
                            .nombre(nombre)
                            .correo(correoNorm)
                            .contrasena(passwordEncoder.encode(contrasena))
                            .rol(rol)
                            .organizacion(org)
                            .activo(true)
                            .fechaRegistro(LocalDateTime.now(clock))
                            .build());
                    log.info("Usuario {} inicial creado: {}", rol, correoNorm);
                    return u;
                });
    }

    private void crearCategoriasBase() {
        if (tipoAlimentoRepository.count() > 0) {
            return;
        }
        tipoAlimentoRepository.saveAll(List.of(
                categoria("Frutas y verduras", "Productos frescos de origen vegetal", "KG"),
                categoria("Panadera", "Pan, pasteles y productos horneados", "UNIDAD"),
                categoria("Lǭcteos", "Leche, yogures y derivados", "L"),
                categoria("Protenas", "Carnes, pollo, pescado y huevos", "KG"),
                categoria("Comidas preparadas", "Platos listos para consumo inmediato", "PORCION"),
                categoria("Granos y abarrotes", "Arroz, granos, enlatados y no perecederos", "KG")
        ));
        log.info("Categoras de alimentos base creadas");
    }

    private void crearOrganizacionesYUsuarios() {
        // Donante 1: Restaurante Sabor Casero
        Organizacion orgDonante1 = obtenerOCrearOrg("901234567-1", "Restaurante Sabor Casero",
                "Centro Histrico, Carrera 7 # 12-40", "contacto@saborcasero.com", "3101234567", true, false, 0);
        crearUsuarioSiNoExiste(Rol.DONANTE, "Chef Martn Prez", "donante@saborcasero.com", "Pass12345", orgDonante1);

        // Donante 2: Panificadora Central SAS
        Organizacion orgDonante2 = obtenerOCrearOrg("901876543-2", "Panificadora Central SAS",
                "Zona Industrial, Calle 26 # 68-15", "donaciones@panificadoracentral.com", "3157654321", true, false, 0);
        crearUsuarioSiNoExiste(Rol.DONANTE, "Gerente Panificadora Central", "donante@panificadoracentral.com", "Pass12345", orgDonante2);

        // Receptora 1: Fundacin Pan Compartido
        Organizacion orgReceptora1 = obtenerOCrearOrg("800987654-3", "Fundacin Pan Compartido",
                "Comuna 4, Manzana 12 Casa 5", "direccion@pancompartido.org", "3209876543", true, false, 0);
        crearUsuarioSiNoExiste(Rol.RECEPTORA, "Hermana Teresa - Pan Compartido", "receptora@pancompartido.org", "Pass12345", orgReceptora1);

        // Receptora 2: Comedor Infantil Esperanza
        Organizacion orgReceptora2 = obtenerOCrearOrg("800345678-4", "Comedor Infantil Esperanza",
                "Barrio El Carmen, Transversal 15 # 3-22", "coordinacion@comedoresperanza.org", "3183456789", true, false, 0);
        crearUsuarioSiNoExiste(Rol.RECEPTORA, "Trabajadora Social Laura", "receptora@comedoresperanza.org", "Pass12345", orgReceptora2);

        // Receptora 3 (Suspendida por 3 strikes para RN08)
        Organizacion orgSuspendida = obtenerOCrearOrg("800555999-5", "Hogar Abuelitos de la Paz",
                "Barrio San Carlos, Carrera 10 # 45-10", "contacto@abuelitospaz.org", "3115559999", true, true, 3);
        crearUsuarioSiNoExiste(Rol.RECEPTORA, "Delegado Abuelitos de la Paz", "suspendido@abuelitospaz.org", "Pass12345", orgSuspendida);

        // Crear 3 strikes vigentes para la organizacin suspendida
        if (strikeRepository.findByOrganizacion_IdOrganizacionOrderByFechaRegistroDesc(orgSuspendida.getIdOrganizacion()).isEmpty()) {
            LocalDateTime ahora = LocalDateTime.now(clock);
            strikeRepository.save(Strike.builder().organizacion(orgSuspendida).nombreStrike("INASISTENCIA_RETIRO")
                    .detalles("No se present a retirar lote asignado").vigente(true).fechaRegistro(ahora.minusDays(5)).build());
            strikeRepository.save(Strike.builder().organizacion(orgSuspendida).nombreStrike("FUERA_DE_FRANJA")
                    .detalles("Llegada 3 horas despus de la franja horaria").vigente(true).fechaRegistro(ahora.minusDays(3)).build());
            strikeRepository.save(Strike.builder().organizacion(orgSuspendida).nombreStrike("INASISTENCIA_RETIRO")
                    .detalles("Reincidencia en inasistencia").vigente(true).fechaRegistro(ahora.minusDays(1)).build());
            log.info("Strikes de prueba registrados para organizacion suspendida");
        }

        // Donante 3: Panadería Sol (Suspendida por 3 strikes para RN08)
        Organizacion orgPanaderiaSol = obtenerOCrearOrg("901555777-6", "Panadería Sol",
                "Calle 10 # 15-30, Barrio El Sol", "contacto@panaderiasol.com", "3123456789", true, true, 3);
        crearUsuarioSiNoExiste(Rol.DONANTE, "Dueño Panadería Sol", "suspendido@panaderiasol.com", "Pass12345", orgPanaderiaSol);
        crearUsuarioSiNoExiste(Rol.DONANTE, "Dueño Panadería Sol", "panaderiasol@alimentared.com", "Pass12345", orgPanaderiaSol);

        if (strikeRepository.findByOrganizacion_IdOrganizacionOrderByFechaRegistroDesc(orgPanaderiaSol.getIdOrganizacion()).isEmpty()) {
            LocalDateTime ahora = LocalDateTime.now(clock);
            strikeRepository.save(Strike.builder().organizacion(orgPanaderiaSol).nombreStrike("ALIMENTO_EN_MAL_ESTADO")
                    .detalles("Lote de pan entregado con presencia de moho").vigente(true).fechaRegistro(ahora.minusDays(6)).build());
            strikeRepository.save(Strike.builder().organizacion(orgPanaderiaSol).nombreStrike("CANCELACION_TARDIA")
                    .detalles("Cancelación unilateral de retiro con menos de 1 hora de anticipación").vigente(true).fechaRegistro(ahora.minusDays(4)).build());
            strikeRepository.save(Strike.builder().organizacion(orgPanaderiaSol).nombreStrike("INCUMPLIMIENTO_CALIDAD")
                    .detalles("Producto reportado en mal estado por la receptora en punto de entrega").vigente(true).fechaRegistro(ahora.minusDays(2)).build());
            log.info("Strikes de prueba registrados para Panadería Sol (Donante suspendida)");
        }
    }

    private Organizacion obtenerOCrearOrg(String nit, String nombre, String ubicacion, String correo,
                                         String telefono, boolean verificada, boolean bloqueada, int strikes) {
        return organizacionRepository.findAll().stream()
                .filter(o -> o.getNit().equalsIgnoreCase(nit))
                .findFirst()
                .orElseGet(() -> organizacionRepository.save(Organizacion.builder()
                        .nit(nit)
                        .nombre(nombre)
                        .ubicacion(ubicacion)
                        .correo(correo)
                        .telefono(telefono)
                        .verificada(verificada)
                        .bloqueada(bloqueada)
                        .strikesVigentes(strikes)
                        .fechaRegistro(LocalDateTime.now(clock))
                        .build()));
    }

    private void crearLotesDePrueba() {
        if (loteRepository.count() > 0) {
            return;
        }

        Organizacion donante1 = organizacionRepository.findAll().stream()
                .filter(o -> "901234567-1".equalsIgnoreCase(o.getNit()))
                .findFirst().orElse(null);
        Organizacion donante2 = organizacionRepository.findAll().stream()
                .filter(o -> "901876543-2".equalsIgnoreCase(o.getNit()))
                .findFirst().orElse(null);

        if (donante1 == null || donante2 == null) {
            return;
        }

        List<TipoAlimento> categorias = tipoAlimentoRepository.findAll();
        TipoAlimento comPreparada = categorias.stream().filter(c -> c.getNombre().toLowerCase().contains("comida")).findFirst().orElse(categorias.get(0));
        TipoAlimento panaderia = categorias.stream().filter(c -> c.getNombre().toLowerCase().contains("panad")).findFirst().orElse(categorias.get(0));
        TipoAlimento frutas = categorias.stream().filter(c -> c.getNombre().toLowerCase().contains("fruta")).findFirst().orElse(categorias.get(0));

        LocalDateTime ahora = LocalDateTime.now(clock);

        loteRepository.save(Lote.builder()
                .nombre("Sopa nutritiva y verduras cocidas")
                .descripcion("Porciones individuales empacadas en recipientes trmicos, aptas para consumo el mismo da.")
                .categoria(comPreparada)
                .donante(donante1)
                .cantidadInicial(new BigDecimal("50.000"))
                .cantidadDisponible(new BigDecimal("50.000"))
                .unidadMinima(new BigDecimal("10.000"))
                .multiplo(new BigDecimal("5.000"))
                .estado(EstadoLote.DISPONIBLE)
                .estadoAlimento(EstadoAlimento.FRESCO)
                .ubicacion("Centro Histrico, Carrera 7 # 12-40")
                .instruccionesRecogida("Ingresar por el muelle de carga posterior. Preguntar por el Chef Martn.")
                .contacto("Martn Prez - 3101234567")
                .fechaRegistro(ahora)
                .fechaCaducidad(ahora.plusHours(6))
                .build());

        loteRepository.save(Lote.builder()
                .nombre("Lote de pan campesino e integral")
                .descripcion("Bolsas selladas de 10 unidades de pan recin horneado del da anterior.")
                .categoria(panaderia)
                .donante(donante2)
                .cantidadInicial(new BigDecimal("100.000"))
                .cantidadDisponible(new BigDecimal("100.000"))
                .unidadMinima(new BigDecimal("20.000"))
                .multiplo(new BigDecimal("10.000"))
                .estado(EstadoLote.DISPONIBLE)
                .estadoAlimento(EstadoAlimento.FRESCO)
                .ubicacion("Zona Industrial, Calle 26 # 68-15")
                .instruccionesRecogida("Reclamar en el rea de despachos con documento de identificacin.")
                .contacto("Bodega Despachos - 3157654321")
                .fechaRegistro(ahora)
                .fechaCaducidad(ahora.plusHours(24))
                .build());

        loteRepository.save(Lote.builder()
                .nombre("Frutas mixtas (Manzanas y Pltanos)")
                .descripcion("Canastillas con fruta en punto ptimo de maduracin.")
                .categoria(frutas)
                .donante(donante1)
                .cantidadInicial(new BigDecimal("40.000"))
                .cantidadDisponible(new BigDecimal("40.000"))
                .unidadMinima(new BigDecimal("10.000"))
                .multiplo(new BigDecimal("5.000"))
                .estado(EstadoLote.DISPONIBLE)
                .estadoAlimento(EstadoAlimento.FRESCO)
                .ubicacion("Centro Histrico, Carrera 7 # 12-40")
                .instruccionesRecogida("Presentarse en recepcin principal con canastillas limpias.")
                .contacto("Martn Prez - 3101234567")
                .fechaRegistro(ahora)
                .fechaCaducidad(ahora.plusHours(3)) // < 4 horas para disparar alerta de rescate urgente
                .build());

        log.info("Lotes de alimentos de prueba creados exitosamente");
    }

    private TipoAlimento categoria(String nombre, String descripcion, String unidad) {
        return TipoAlimento.builder().nombre(nombre).descripcion(descripcion).unidadMedida(unidad).build();
    }
}
