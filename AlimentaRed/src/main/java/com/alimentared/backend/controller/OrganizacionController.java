package com.alimentared.backend.controller;

import com.alimentared.backend.config.UsuarioPrincipal;
import com.alimentared.backend.dto.request.ApelacionStrikeRequest;
import com.alimentared.backend.dto.request.OrganizacionRequest;
import com.alimentared.backend.dto.response.OrganizacionResponse;
import com.alimentared.backend.dto.response.PageResponse;
import com.alimentared.backend.dto.response.StrikeResponse;
import com.alimentared.backend.service.OrganizacionService;
import com.alimentared.backend.service.StrikeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Organizaciones", description = "Gestión de organizaciones y strikes (RF02, RN08, RN12)")
@RestController
@RequestMapping("/api/organizaciones")
@RequiredArgsConstructor
public class OrganizacionController {

    private final OrganizacionService organizacionService;
    private final StrikeService strikeService;

    @Operation(summary = "Listar organizaciones", description = "Filtro opcional por estado de verificación")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','COORDINADOR')")
    @GetMapping
    public ResponseEntity<PageResponse<OrganizacionResponse>> listar(
            @RequestParam(required = false) Boolean verificada,
            @ParameterObject @PageableDefault(size = 20, sort = "nombre") Pageable pageable) {
        return ResponseEntity.ok(organizacionService.listarOrganizaciones(verificada, pageable));
    }

    @Operation(summary = "Consultar organización por id")
    @GetMapping("/{id}")
    public ResponseEntity<OrganizacionResponse> obtener(@PathVariable Long id,
                                                        @AuthenticationPrincipal UsuarioPrincipal principal) {
        return ResponseEntity.ok(organizacionService.consultarOrganizacionPorId(id, principal.getId()));
    }

    @Operation(summary = "Actualizar datos de la organización")
    @PutMapping("/{id}")
    public ResponseEntity<OrganizacionResponse> actualizar(@PathVariable Long id,
                                                           @Valid @RequestBody OrganizacionRequest request,
                                                           @AuthenticationPrincipal UsuarioPrincipal principal) {
        return ResponseEntity.ok(organizacionService.actualizarOrganizacion(id, request, principal.getId()));
    }

    @Operation(summary = "Verificar organización", description = "Habilita a la organización para operar por completo")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PatchMapping("/{id}/verificar")
    public ResponseEntity<OrganizacionResponse> verificar(@PathVariable Long id,
                                                          @AuthenticationPrincipal UsuarioPrincipal principal) {
        return ResponseEntity.ok(organizacionService.verificarOrganizacion(id, principal.getId()));
    }

    @Operation(summary = "Listar todos los strikes del sistema", description = "Exclusivo para coordinación y administración")
    @PreAuthorize("hasAnyRole('COORDINADOR','ADMINISTRADOR')")
    @GetMapping({"/strikes", "/strikes/todos"})
    public ResponseEntity<List<StrikeResponse>> listarTodosStrikes(@AuthenticationPrincipal UsuarioPrincipal principal) {
        return ResponseEntity.ok(strikeService.listarTodosStrikes(principal.getId()));
    }

    @Operation(summary = "Consultar strikes de la organización")
    @GetMapping("/{id}/strikes")
    public ResponseEntity<List<StrikeResponse>> strikes(@PathVariable Long id,
                                                        @AuthenticationPrincipal UsuarioPrincipal principal) {
        return ResponseEntity.ok(strikeService.consultarStrikes(id, principal.getId()));
    }

    @Operation(summary = "Evaluar apelación de un strike",
            description = "RN12: si se aprueba, el strike se anula y la cuenta se reactiva si baja del máximo")
    @PreAuthorize("hasAnyRole('COORDINADOR','ADMINISTRADOR')")
    @PatchMapping("/{id}/strikes/apelar")
    public ResponseEntity<StrikeResponse> apelar(@PathVariable Long id,
                                                 @Valid @RequestBody ApelacionStrikeRequest request,
                                                 @AuthenticationPrincipal UsuarioPrincipal principal) {
        return ResponseEntity.ok(strikeService.apelarStrike(id, request, principal.getId()));
    }
}
