package com.alimentared.backend.repository;

import com.alimentared.backend.entity.Strike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StrikeRepository extends JpaRepository<Strike, Long> {

    List<Strike> findByOrganizacion_IdOrganizacionOrderByFechaRegistroDesc(Long idOrganizacion);

    List<Strike> findAllByOrderByFechaRegistroDesc();

    long countByFechaRegistroGreaterThanEqualAndFechaRegistroLessThan(LocalDateTime desde, LocalDateTime hasta);

    boolean existsByAsignacion_IdAsignacion(Long idAsignacion);
}
