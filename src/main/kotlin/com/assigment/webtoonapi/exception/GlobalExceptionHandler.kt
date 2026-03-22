package com.assigment.webtoonapi.exception

import jakarta.persistence.LockTimeoutException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(WebtoonException::class)
    fun handleWebtoonException(e: WebtoonException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ErrorResponse.of(e.errorCode))
    }

    /**
     * DataIntegrityViolationException: DB UNIQUE Constraint 위반 (최후 방어선)
     *
     * Redis 분산 락(Layer 1)과 비관적 락(Layer 2)이 주 방어선이지만,
     * Redis 장애 등 극단적인 케이스에서 DB UNIQUE Constraint가 최후로 차단한다.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(ErrorCode.INTERNAL_ERROR.status)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR))
    }

    /**
     * LockTimeoutException: DB 비관적 락 대기 타임아웃 (5초)
     *
     * 동일 유저가 서로 다른 에피소드를 대량으로 동시 구매할 때
     * coinBalance 보호를 위한 FOR UPDATE 대기가 초과되면 발생한다.
     */
    @ExceptionHandler(LockTimeoutException::class)
    fun handleLockTimeout(e: LockTimeoutException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(ErrorCode.LOCK_TIMEOUT.status)
            .body(ErrorResponse.of(ErrorCode.LOCK_TIMEOUT))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(ErrorCode.INTERNAL_ERROR.status)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR))
    }
}
