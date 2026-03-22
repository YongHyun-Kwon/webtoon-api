package com.assigment.webtoonapi.dto

import com.assigment.webtoonapi.domain.UserEpisodeAccess
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "에피소드 열람 권한 조회 응답")
data class AccessResponse(
    @Schema(description = "사용자 ID", example = "1")
    val userId: Long,

    @Schema(description = "에피소드 ID", example = "42")
    val episodeId: Long,

    @Schema(description = "열람 권한 보유 여부. 구매 이력이 있는 경우 항상 `true`를 반환", example = "true")
    val hasAccess: Boolean,

    @Schema(description = "열람 권한 부여 일시 (구매 일시와 동일)", example = "2026-03-22T10:30:00")
    val accessGrantedAt: LocalDateTime,
) {
    companion object {
        fun from(access: UserEpisodeAccess) = AccessResponse(
            userId = access.user.id,
            episodeId = access.episode.id,
            hasAccess = true,
            accessGrantedAt = access.accessGrantedAt,
        )
    }
}