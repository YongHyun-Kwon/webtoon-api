package com.assigment.webtoonapi.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "purchases",
    uniqueConstraints = [
        // 중복 결제 방지 핵심: DB 레벨 최후 방어선
        UniqueConstraint(name = "uq_purchase_user_episode", columnNames = ["user_id", "episode_id"])
    ],
    indexes = [
        Index(name = "idx_purchase_user_id", columnList = "user_id"),
        Index(name = "idx_purchase_episode_id", columnList = "episode_id"),
    ]
)
class Purchase(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "episode_id", nullable = false)
    val episode: Episode,

    // 구매 시점 가격 스냅샷 (이후 에피소드 가격 변경과 무관)
    @Column(nullable = false)
    val price: Int,

    @Column(nullable = false)
    val purchasedAt: LocalDateTime = LocalDateTime.now(),
)
