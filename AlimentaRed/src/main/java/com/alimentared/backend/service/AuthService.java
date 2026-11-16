package com.alimentared.backend.service;

import com.alimentared.backend.dto.request.LoginRequest;
import com.alimentared.backend.dto.request.RegisterRequest;
import com.alimentared.backend.dto.request.RestablecerContrasenaRequest;
import com.alimentared.backend.dto.response.AuthResponse;

public interface AuthService {

    AuthResponse iniciarSesion(LoginRequest request);

    AuthResponse registrarUsuario(RegisterRequest request);

    void restablecerContrasena(RestablecerContrasenaRequest request);
}
