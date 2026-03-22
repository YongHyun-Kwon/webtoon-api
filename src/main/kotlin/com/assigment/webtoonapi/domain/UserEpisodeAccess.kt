package com.assigment.webtoonapi.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "user_episode_access",
    uniqueConstraints = [
        // Purchase 없는 고아 Access 및 중복 Access 방지
        UniqueConstraint(name = "uq_access_user_episode", columnNames = ["user_id", "episode_id"])
    ],
    indexes = [
        Index(name = "idx_access_user_id", columnList = "user_id"),
        Index(name = "idx_access_episode_id", columnList = "episode_id"),
    ]
)
class UserEpisodeAccess(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "episode_id", nullable = false)
    val episode: Episode,

    @Column(nullable = false)
    val accessGrantedAt: LocalDateTime = LocalDateTime.now(),
)
