package com.assigment.webtoonapi.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 100)
    val username: String,

    @Column(nullable = false, unique = true, length = 255)
    val email: String,

    @Column(nullable = false)
    var coinBalance: Int,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    fun deductCoins(amount: Int) {
        require(coinBalance >= amount) { "코인 잔액이 부족합니다. 현재: $coinBalance, 필요: $amount" }
        coinBalance -= amount
    }
}
