package com.arcbank.cbs.transaccion.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.arcbank.cbs.transaccion.model.Transaccion;

public interface TransaccionRepository extends JpaRepository<Transaccion, Integer> {

    @Query("SELECT t FROM Transaccion t WHERE t.idCuentaOrigen = :idCuenta OR t.idCuentaDestino = :idCuenta")
    List<Transaccion> findPorCuenta(@Param("idCuenta") Integer idCuenta);

    Optional<Transaccion> findByReferencia(String referencia);

    Optional<Transaccion> findByCodigoReferencia(String codigoReferencia);
}