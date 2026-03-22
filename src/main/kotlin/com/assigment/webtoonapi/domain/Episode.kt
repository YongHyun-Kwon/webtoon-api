package com.assigment.webtoonapi.domain

import jakarta.persistence.*
import jakarta.persistence.Id
import java.time.LocalDateTime

@Entity
@Table(name = "episodes")
class Episode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 255)
    val title: String,

    @Column(nullable = false)
    val price: Int,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
