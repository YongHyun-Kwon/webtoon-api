package com.assigment.webtoonapi.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    EPISODE_NOT_FOUND(HttpStatus.NOT_FOUND, "에피소드를 찾을 수 없습니다."),
    INSUFFICIENT_COINS(HttpStatus.PAYMENT_REQUIRED, "코인이 부족합니다."),
    ACCESS_NOT_FOUND(HttpStatus.NOT_FOUND, "열람 권한이 없습니다. 에피소드를 먼저 구매해주세요."),
    LOCK_TIMEOUT(HttpStatus.CONFLICT, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
}
