package com.assigment.webtoonapi.service

import com.assigment.webtoonapi.domain.Purchase
import com.assigment.webtoonapi.domain.UserEpisodeAccess
import com.assigment.webtoonapi.dto.AccessResponse
import com.assigment.webtoonapi.dto.PurchaseResponse
import com.assigment.webtoonapi.exception.ErrorCode
import com.assigment.webtoonapi.exception.WebtoonException
import com.assigment.webtoonapi.repository.EpisodeRepository
import com.assigment.webtoonapi.repository.PurchaseRepository
import com.assigment.webtoonapi.repository.UserEpisodeAccessRepository
import com.assigment.webtoonapi.repository.UserRepository
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.TimeUnit

@Service
class PurchaseService(
    private val userRepository: UserRepository,
    private val episodeRepository: EpisodeRepository,
    private val purchaseRepository: PurchaseRepository,
    private val userEpisodeAccessRepository: UserEpisodeAccessRepository,
    private val redissonClient: RedissonClient,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    companion object {
        private const val PURCHASE_LOCK_PREFIX = "purchase:lock:"
        private const val LOCK_WAIT_SECONDS = 5L   // Redis 락 획득 대기 시간
        private const val LOCK_TTL_SECONDS = 10L   // 서버 크래시 시 자동 해제 TTL
    }

    /**
     * 에피소드 구매 + 즉시 열람 권한 부여
     *
     * [방어 계층]
     * Layer 1 — Redis 분산 락 "purchase:lock:{userId}:{episodeId}"
     *   동일 user+episode 동시 요청 차단. 에피소드 단위 격리로 다른 에피소드는 병렬 가능.
     *
     * Layer 2 — DB 비관적 락 (SELECT users FOR UPDATE)
     *   동일 유저가 다른 에피소드를 동시 구매할 때 coinBalance 경합 방지.
     *
     * Layer 3 — DB UNIQUE Constraint (purchases.user_id + episode_id)
     *   Redis 장애 등 극단적 케이스의 최후 방어.
     *
     * [흐름]
     * Redis 락 획득 → 기존 구매 확인(멱등성) → User FOR UPDATE → 코인 차감 + 저장 → 커밋 → 락 해제
     */
    fun purchase(userId: Long, episodeId: Long): PurchaseResponse {
        val lock = redissonClient.getLock("$PURCHASE_LOCK_PREFIX$userId:$episodeId")

        // tryLock(waitTime, leaseTime, unit): waitTime 동안 락 획득 시도, 성공 시 leaseTime 후 자동 해제
        val acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_TTL_SECONDS, TimeUnit.SECONDS)
        if (!acquired) {
            throw WebtoonException(ErrorCode.LOCK_TIMEOUT)
        }

        try {
            // 같은 클래스 내부 호출 시 @Transactional 프록시가 동작하지 않으므로 TransactionTemplate으로 직접 관리
            return transactionTemplate.execute { executePurchase(userId, episodeId) }!!
        } finally {
            // 트랜잭션 커밋/롤백 완료 후 락 해제 (finally로 예외 발생 시에도 반드시 해제)
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }

    private fun executePurchase(userId: Long, episodeId: Long): PurchaseResponse {
        // 멱등성 체크: Redis 락으로 직렬화된 이후이므로 여기서 감지하면 안전하게 반환 가능
        purchaseRepository.findByUserIdAndEpisodeId(userId, episodeId)?.let {
            return PurchaseResponse.from(it, isNew = false)
        }

        // Layer 2: FOR UPDATE로 User 행 잠금 → coinBalance 동시 차감 방지
        val user = userRepository.findByIdWithPessimisticLock(userId)
            ?: throw WebtoonException(ErrorCode.USER_NOT_FOUND)

        val episode = episodeRepository.findById(episodeId)
            .orElseThrow { WebtoonException(ErrorCode.EPISODE_NOT_FOUND) }

        if (user.coinBalance < episode.price) {
            throw WebtoonException(ErrorCode.INSUFFICIENT_COINS)
        }

        // Dirty Checking: 트랜잭션 커밋 시 변경된 coinBalance를 자동으로 UPDATE
        user.deductCoins(episode.price)

        // Purchase + Access를 동일 트랜잭션에서 저장 → 원자성 보장
        val purchase = purchaseRepository.save(
            Purchase(user = user, episode = episode, price = episode.price)
        )
        userEpisodeAccessRepository.save(
            UserEpisodeAccess(user = user, episode = episode)
        )

        return PurchaseResponse.from(purchase, isNew = true)
    }

    /**
     * 열람 권한 조회
     *
     * purchase()와 달리 읽기 전용이므로 락 불필요.
     * 구매 직후 즉시 호출 가능 (동일 트랜잭션에서 Access가 생성됨).
     */
    @Transactional(readOnly = true)
    fun getAccess(userId: Long, episodeId: Long): AccessResponse {
        if (!userRepository.existsById(userId)) {
            throw WebtoonException(ErrorCode.USER_NOT_FOUND)
        }
        if (!episodeRepository.existsById(episodeId)) {
            throw WebtoonException(ErrorCode.EPISODE_NOT_FOUND)
        }
        val access = userEpisodeAccessRepository.findByUserIdAndEpisodeId(userId, episodeId)
            ?: throw WebtoonException(ErrorCode.ACCESS_NOT_FOUND)

        return AccessResponse.from(access)
    }
}
