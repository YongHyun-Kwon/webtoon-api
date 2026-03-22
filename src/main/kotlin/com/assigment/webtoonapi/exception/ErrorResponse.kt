package com.assigment.webtoonapi.exception

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "에러 응답")
data class ErrorResponse(
    @Schema(
        description = "에러 코드",
        example = "INSUFFICIENT_COINS",
        allowableValues = ["USER_NOT_FOUND", "EPISODE_NOT_FOUND", "INSUFFICIENT_COINS", "ACCESS_NOT_FOUND", "LOCK_TIMEOUT", "INTERNAL_ERROR"]
    )
    val code: String,

    @Schema(description = "에러 메시지", example = "코인이 부족합니다.")
    val message: String,

    @Schema(description = "에러 발생 일시", example = "2026-03-22T10:30:00")
    val timestamp: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun of(errorCode: ErrorCode) = ErrorResponse(
            code = errorCode.name,
            message = errorCode.message,
        )
    }
}