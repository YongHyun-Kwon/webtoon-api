package com.assigment.webtoonapi.repository

import com.assigment.webtoonapi.domain.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Long> {

    /**
     * 비관적 쓰기 락으로 User 행을 조회한다.
     *
     * 생성되는 SQL: SELECT * FROM users WHERE id = ? FOR UPDATE
     *
     * 동일 userId로 들어오는 모든 요청이 이 지점에서 직렬화된다.
     * - 락 보유 트랜잭션이 커밋/롤백할 때까지 다른 트랜잭션은 대기
     * - application.properties의 jakarta.persistence.lock.timeout(5000ms) 초과 시 LockTimeoutException
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    fun findByIdWithPessimisticLock(id: Long): User?
}
