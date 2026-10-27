package com.alimentared.backend.controller;

import com.alimentared.backend.config.UsuarioPrincipal;
import com.alimentared.backend.dto.response.EntregaResponse;
import com.alimentared.backend.dto.response.PageResponse;
import com.alimentared.backend.dto.response.ReporteImpactoResponse;
import com.alimentared.backend.service.ReporteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Reportes", description = "Indicadores de impacto e historial de donaciones (RF09)")
@RestController
@RequestMapping("/api/reportes")
@RequiredArgsConstructor
public class ReporteController {

    private final ReporteService reporteService;

    @Operation(summary = "Indicadores de impacto por periodo y categoría",
            description = "Alimentos recuperados, tiempos de respuesta y strikes. Por defecto, últimos 30 días")
    @PreAuthorize("hasAnyRole('COORDINADOR','ADMINISTRADOR','DONANTE','RECEPTORA')")
    @GetMapping("/impacto")
    public ResponseEntity<ReporteImpactoResponse> impacto(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long categoriaId) {
        return ResponseEntity.ok(reporteService.consultarImpacto(desde, hasta, categoriaId));
    }

    @Operation(summary = "Historial de donaciones entregadas",
            description = "Donante: sus lotes. Receptora: lo recibido. Coordinación: todo")
    @GetMapping("/historial")
    public ResponseEntity<PageResponse<EntregaResponse>> historial(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long categoriaId,
            @ParameterObject @PageableDefault(size = 20, sort = "fechaEntrega", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UsuarioPrincipal principal) {
        return ResponseEntity.ok(reporteService.consultarHistorial(desde, hasta, categoriaId, principal.getId(), pageable));
    }
}
