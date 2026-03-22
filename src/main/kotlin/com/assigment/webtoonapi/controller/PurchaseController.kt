package com.assigment.webtoonapi.controller

import com.assigment.webtoonapi.dto.AccessResponse
import com.assigment.webtoonapi.dto.PurchaseResponse
import com.assigment.webtoonapi.exception.ErrorResponse
import com.assigment.webtoonapi.service.PurchaseService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "에피소드 구매/열람", description = "웹툰 에피소드 구매 및 열람 권한 관리 API")
@RestController
@RequestMapping("/users/{userId}/episodes/{episodeId}")
class PurchaseController(
    private val purchaseService: PurchaseService,
) {

    @Operation(
        summary = "에피소드 구매",
        description = """
            코인을 사용해 에피소드를 구매합니다.

            멱등성 보장: 네트워크 오류 등으로 인한 재시도 요청도 안전하게 처리
            - 신규 구매: `201 Created` + `isNew: true`
            - 기구매 재요청: `200 OK` + `isNew: false`

            동시성 제어: Redis 분산 락 + DB 비관적 락으로 중복 구매를 방지
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "신규 구매 성공",
            content = [Content(schema = Schema(implementation = PurchaseResponse::class))]
        ),
        ApiResponse(
            responseCode = "200",
            description = "기구매 에피소드 재요청 (멱등성 처리)",
            content = [Content(schema = Schema(implementation = PurchaseResponse::class))]
        ),
        ApiResponse(
            responseCode = "402",
            description = "코인 잔액 부족 (INSUFFICIENT_COINS)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))]
        ),
        ApiResponse(
            responseCode = "404",
            description = "사용자 또는 에피소드를 찾을 수 없음 (USER_NOT_FOUND / EPISODE_NOT_FOUND)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))]
        ),
        ApiResponse(
            responseCode = "409",
            description = "동시 요청 과다 — 잠시 후 재시도 (LOCK_TIMEOUT)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))]
        ),
        ApiResponse(
            responseCode = "500",
            description = "서버 내부 오류 (INTERNAL_ERROR)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))]
        ),
    )
    @PostMapping("/purchase")
    fun purchase(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @PathVariable userId: Long,
        @Parameter(description = "에피소드 ID", example = "42", required = true)
        @PathVariable episodeId: Long,
    ): ResponseEntity<PurchaseResponse> {
        val response = purchaseService.purchase(userId, episodeId)
        val status = if (response.isNew) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(response)
    }

    @Operation(
        summary = "열람 권한 조회",
        description = """
            에피소드 열람 권한을 확인
    
            구매 직후 즉시 호출해도 `200 OK`를 반환
            구매와 열람 권한 부여가 동일 트랜잭션 내에서 처리
    
            미구매 에피소드를 조회하면 `404 ACCESS_NOT_FOUND`를 반환
        """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "열람 권한 있음",
            content = [Content(schema = Schema(implementation = AccessResponse::class))]
        ),
        ApiResponse(
            responseCode = "404",
            description = "열람 권한 없음 — 에피소드 미구매 (ACCESS_NOT_FOUND)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))]
        ),
        ApiResponse(
            responseCode = "500",
            description = "서버 내부 오류 (INTERNAL_ERROR)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))]
        ),
    )
    @GetMapping("/access")
    fun getAccess(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @PathVariable userId: Long,
        @Parameter(description = "에피소드 ID", example = "42", required = true)
        @PathVariable episodeId: Long,
    ): ResponseEntity<AccessResponse> {
        val response = purchaseService.getAccess(userId, episodeId)
        return ResponseEntity.ok(response)
    }
}