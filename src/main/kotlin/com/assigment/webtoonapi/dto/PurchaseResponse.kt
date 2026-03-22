package com.assigment.webtoonapi.dto

import com.assigment.webtoonapi.domain.Purchase
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "에피소드 구매 응답")
data class PurchaseResponse(
    @Schema(description = "구매 ID", example = "101")
    val purchaseId: Long,

    @Schema(description = "사용자 ID", example = "1")
    val userId: Long,

    @Schema(description = "에피소드 ID", example = "42")
    val episodeId: Long,

    @Schema(description = "구매 시점의 에피소드 가격 (코인)", example = "3")
    val price: Int,

    @Schema(description = "구매 일시", example = "2026-03-22T10:30:00")
    val purchasedAt: LocalDateTime,

    @Schema(
        description = "신규 구매 여부. `true`이면 이번 요청에서 새로 구매된 것(201), `false`이면 이미 구매된 에피소드의 멱등성 응답(200)",
        example = "true"
    )
    val isNew: Boolean,
) {
    companion object {
        fun from(purchase: Purchase, isNew: Boolean) = PurchaseResponse(
            purchaseId = purchase.id,
            userId = purchase.user.id,
            episodeId = purchase.episode.id,
            price = purchase.price,
            purchasedAt = purchase.purchasedAt,
            isNew = isNew,
        )
    }
}