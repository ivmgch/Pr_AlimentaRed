package com.alimentared.backend.controller;

import com.alimentared.backend.dto.request.LoginRequest;
import com.alimentared.backend.dto.request.RegisterRequest;
import com.alimentared.backend.dto.request.RestablecerContrasenaRequest;
import com.alimentared.backend.dto.response.AuthResponse;
import com.alimentared.backend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Autenticación", description = "Inicio de sesión y registro de organizaciones (RF01, RF02)")
@SecurityRequirements
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Iniciar sesión", description = "Devuelve un token JWT. Cuentas con 3 strikes quedan bloqueadas (RN08)")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.iniciarSesion(request));
    }

    @Operation(summary = "Registrar organización donante o receptora",
            description = "La organización queda pendiente de verificación por un administrador")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.registrarUsuario(request));
    }

    @Operation(summary = "Restablecer contraseña",
            description = "Permite a cualquier usuario (donante, receptora, coordinador o administrador) restablecer su contraseña olvidada")
    @PostMapping("/restablecer-contrasena")
    public ResponseEntity<Void> restablecerContrasena(@Valid @RequestBody RestablecerContrasenaRequest request) {
        authService.restablecerContrasena(request);
        return ResponseEntity.noContent().build();
    }
}
