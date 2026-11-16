package com.alimentared.backend.service.impl;

import com.alimentared.backend.config.JwtService;
import com.alimentared.backend.config.UsuarioPrincipal;
import com.alimentared.backend.dto.request.LoginRequest;
import com.alimentared.backend.dto.request.RegisterRequest;
import com.alimentared.backend.dto.request.RestablecerContrasenaRequest;
import com.alimentared.backend.dto.response.AuthResponse;
import com.alimentared.backend.entity.Organizacion;
import com.alimentared.backend.entity.Usuario;
import com.alimentared.backend.entity.enums.EntidadAuditada;
import com.alimentared.backend.entity.enums.Rol;
import com.alimentared.backend.exception.AccountBlockedException;
import com.alimentared.backend.exception.BusinessRuleException;
import com.alimentared.backend.exception.DuplicateResourceException;
import com.alimentared.backend.exception.ResourceNotFoundException;
import com.alimentared.backend.mapper.OrganizacionMapper;
import com.alimentared.backend.mapper.UsuarioMapper;
import com.alimentared.backend.repository.OrganizacionRepository;
import com.alimentared.backend.repository.UsuarioRepository;
import com.alimentared.backend.service.AuditoriaService;
import com.alimentared.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Set<Rol> ROLES_AUTORREGISTRO = Set.of(Rol.DONANTE, Rol.RECEPTORA);
    private static final String TIPO_TOKEN = "Bearer";

    private final UsuarioRepository usuarioRepository;
    private final OrganizacionRepository organizacionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UsuarioMapper usuarioMapper;
    private final OrganizacionMapper organizacionMapper;
    private final AuditoriaService auditoriaService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public AuthResponse iniciarSesion(LoginRequest request) {
        Usuario usuario = usuarioRepository.findWithOrganizacionByCorreoIgnoreCase(request.correo().trim())
                .filter(u -> passwordEncoder.matches(request.contrasena(), u.getContrasena()))
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (!usuario.isActivo()) {
            throw new AccountBlockedException("El usuario está inactivo");
        }
        // RN08: bloqueo de acceso al acumular el máximo de strikes.
        if (usuario.getOrganizacion() != null && usuario.getOrganizacion().isBloqueada()) {
            throw new AccountBlockedException("Cuenta suspendida por acumulación de 3 strikes");
        }
        return construirRespuesta(usuario);
    }

    @Override
    @Transactional
    public AuthResponse registrarUsuario(RegisterRequest request) {
        if (!ROLES_AUTORREGISTRO.contains(request.rol())) {
            throw new BusinessRuleException("RF01", "Solo pueden registrarse organizaciones donantes o receptoras");
        }
        String correo = request.correo().trim().toLowerCase();
        if (usuarioRepository.existsByCorreoIgnoreCase(correo)) {
            throw new DuplicateResourceException("Ya existe un usuario registrado con el correo " + correo);
        }
        String nit = request.organizacion().nit().trim();
        if (organizacionRepository.existsByNit(nit)) {
            throw new DuplicateResourceException("Ya existe una organización registrada con el NIT " + nit);
        }

        LocalDateTime ahora = LocalDateTime.now(clock);
        Organizacion organizacion = organizacionMapper.toEntity(request.organizacion());
        organizacion.setFechaRegistro(ahora);
        organizacion = organizacionRepository.save(organizacion);

        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .nombre(request.nombre().trim())
                .correo(correo)
                .contrasena(passwordEncoder.encode(request.contrasena()))
                .rol(request.rol())
                .organizacion(organizacion)
                .fechaRegistro(ahora)
                .build());

        auditoriaService.registrar(EntidadAuditada.ORGANIZACION, organizacion.getIdOrganizacion(), null,
                "PENDIENTE_VERIFICACION", usuario, "Registro de organización " + request.rol());
        return construirRespuesta(usuario);
    }

    @Override
    @Transactional
    public void restablecerContrasena(RestablecerContrasenaRequest request) {
        String correo = request.correo().trim().toLowerCase();
        Usuario usuario = usuarioRepository.findWithOrganizacionByCorreoIgnoreCase(correo)
                .orElseThrow(() -> new ResourceNotFoundException("No existe ninguna cuenta registrada con el correo: " + correo));

        if (!usuario.isActivo()) {
            throw new AccountBlockedException("La cuenta de usuario se encuentra inactiva");
        }
        if (passwordEncoder.matches(request.contrasenaNueva(), usuario.getContrasena())) {
            throw new BusinessRuleException("La nueva contraseña debe ser distinta a la actual");
        }

        usuario.setContrasena(passwordEncoder.encode(request.contrasenaNueva()));
        usuarioRepository.save(usuario);
    }

    private AuthResponse construirRespuesta(Usuario usuario) {
        String token = jwtService.generarToken(UsuarioPrincipal.from(usuario));
        return new AuthResponse(token, TIPO_TOKEN, jwtService.getExpiracionSegundos(), usuarioMapper.toResponse(usuario));
    }
}
