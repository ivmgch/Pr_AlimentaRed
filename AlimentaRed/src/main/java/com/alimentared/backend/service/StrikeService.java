package com.alimentared.backend.service;

import com.alimentared.backend.dto.request.ApelacionStrikeRequest;
import com.alimentared.backend.dto.response.StrikeResponse;
import com.alimentared.backend.entity.Asignacion;
import com.alimentared.backend.entity.Organizacion;
import com.alimentared.backend.entity.Strike;
import com.alimentared.backend.entity.Usuario;

import java.util.List;

public interface StrikeService {

    /** RN08: registra la penalización y bloquea la cuenta al alcanzar el máximo. */
    Strike registrarStrike(Organizacion organizacion, Asignacion asignacion, String nombre,
                           String detalles, Usuario responsable);

    List<StrikeResponse> consultarStrikes(Long organizacionId, Long usuarioId);

    /** RN12: consulta de todos los strikes para la coordinación y administración. */
    List<StrikeResponse> listarTodosStrikes(Long usuarioId);

    /** RN12: evaluación de la apelación por parte de la coordinación. */
    StrikeResponse apelarStrike(Long organizacionId, ApelacionStrikeRequest request, Long usuarioId);

    boolean verificarBloqueoCuenta(Long organizacionId);
}
