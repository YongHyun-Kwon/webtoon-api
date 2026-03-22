package com.assigment.webtoonapi.repository

import com.assigment.webtoonapi.domain.Purchase
import org.springframework.data.jpa.repository.JpaRepository

interface PurchaseRepository : JpaRepository<Purchase, Long> {

    // 멱등성 체크 및 중복 구매 감지에 사용
    // idx_purchase_user_id 인덱스 활용
    fun findByUserIdAndEpisodeId(userId: Long, episodeId: Long): Purchase?
}
