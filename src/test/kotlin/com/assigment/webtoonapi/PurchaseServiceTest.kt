package com.assigment.webtoonapi

import com.assigment.webtoonapi.domain.Episode
import com.assigment.webtoonapi.domain.User
import com.assigment.webtoonapi.exception.ErrorCode
import com.assigment.webtoonapi.exception.WebtoonException
import com.assigment.webtoonapi.repository.EpisodeRepository
import com.assigment.webtoonapi.repository.PurchaseRepository
import com.assigment.webtoonapi.repository.UserEpisodeAccessRepository
import com.assigment.webtoonapi.repository.UserRepository
import com.assigment.webtoonapi.service.PurchaseService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * PurchaseService 통합 테스트
 *
 * [테스트 클래스에 @Transactional을 붙이지 않는 이유]
 * 서비스 메서드(@Transactional REQUIRED)가 테스트의 외부 트랜잭션에 합류하면
 * 실제 COMMIT이 발생하지 않는다. 결과적으로 두 번째 purchase() 호출에서
 * "기구매 없음"으로 잘못 판단해 isNew=true를 반환하는 오류가 생긴다.
 * → 각 service call이 독립 트랜잭션으로 COMMIT되도록 @Transactional 미적용,
 *   @AfterEach에서 직접 정리한다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PurchaseServiceTest {

    companion object {
        @Container
        @JvmStatic
        val redisContainer: GenericContainer<*> = GenericContainer<Nothing>("redis:7-alpine").apply {
            withExposedPorts(6379)
        }

        @DynamicPropertySource
        @JvmStatic
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redisContainer.host }
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
        }
    }

    @Autowired lateinit var purchaseService: PurchaseService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var episodeRepository: EpisodeRepository
    @Autowired lateinit var purchaseRepository: PurchaseRepository
    @Autowired lateinit var userEpisodeAccessRepository: UserEpisodeAccessRepository

    private lateinit var user: User
    private lateinit var episode: Episode

    @BeforeEach
    fun setUp() {
        // FK 제약 순서에 맞게 삭제
        userEpisodeAccessRepository.deleteAll()
        purchaseRepository.deleteAll()
        userRepository.deleteAll()
        episodeRepository.deleteAll()

        user = userRepository.save(User(username = "테스터", email = "test@test.com", coinBalance = 1000))
        episode = episodeRepository.save(Episode(title = "1화", price = 100))
    }

    @AfterEach
    fun tearDown() {
        userEpisodeAccessRepository.deleteAll()
        purchaseRepository.deleteAll()
        userRepository.deleteAll()
        episodeRepository.deleteAll()
    }

    // =========================================================================
    // 에피소드 구매
    // =========================================================================

    @Nested
    @DisplayName("에피소드 구매 — 정상 처리")
    inner class SuccessProcess {

        @Test
        @DisplayName("구매 시 Purchase 레코드가 생성된다")
        fun `구매 시 Purchase 레코드가 생성된다`() {
            // Act
            purchaseService.purchase(user.id, episode.id)

            // Assert
            val purchase = purchaseRepository.findByUserIdAndEpisodeId(user.id, episode.id)
            assertThat(purchase).isNotNull
        }

        @Test
        @DisplayName("구매 시 UserEpisodeAccess 레코드가 생성된다")
        fun `구매 시 UserEpisodeAccess 레코드가 생성된다`() {
            // Act
            purchaseService.purchase(user.id, episode.id)

            // Assert
            val access = userEpisodeAccessRepository.findByUserIdAndEpisodeId(user.id, episode.id)
            assertThat(access).isNotNull
        }

        @Test
        @DisplayName("구매 시 에피소드 가격만큼 코인이 차감된다")
        fun `구매 시 에피소드 가격만큼 코인이 차감된다`() {
            // Arrange
            val beforeBalance = user.coinBalance

            // Act
            purchaseService.purchase(user.id, episode.id)

            // Assert
            val afterBalance = userRepository.findById(user.id).get().coinBalance
            assertThat(afterBalance).isEqualTo(beforeBalance - episode.price)
        }

        @Test
        @DisplayName("신규 구매 시 isNew=true를 반환한다")
        fun `신규 구매 시 isNew=true를 반환한다`() {
            // Act
            val response = purchaseService.purchase(user.id, episode.id)

            // Assert
            assertThat(response.isNew).isTrue()
        }

        @Test
        @DisplayName("구매 직후 열람 권한이 즉시 존재한다")
        fun `구매 직후 열람 권한이 즉시 존재한다`() {
            // Arrange
            purchaseService.purchase(user.id, episode.id)

            // Act
            val response = purchaseService.getAccess(user.id, episode.id)

            // Assert
            assertThat(response.hasAccess).isTrue()
        }

        @Test
        @DisplayName("구매 시 구매 시점의 에피소드 가격이 스냅샷으로 저장된다")
        fun `구매 시 구매 시점의 에피소드 가격이 스냅샷으로 저장된다`() {
            // Act
            val response = purchaseService.purchase(user.id, episode.id)

            // Assert
            assertThat(response.price).isEqualTo(episode.price)
        }

        @Test
        @DisplayName("코인 잔액이 에피소드 가격과 정확히 같을 때 구매에 성공한다")
        fun `코인 잔액이 에피소드 가격과 정확히 같을 때 구매에 성공한다`() {
            // Arrange: coinBalance == price (경계값)
            val exactUser = userRepository.save(User(username = "딱맞는 유저", email = "exact@test.com", coinBalance = episode.price))

            // Act
            val response = purchaseService.purchase(exactUser.id, episode.id)

            // Assert
            assertThat(response.isNew).isTrue()
            assertThat(userRepository.findById(exactUser.id).get().coinBalance).isEqualTo(0)
        }
    }

    // =========================================================================
    // 멱등성: 중복 요청 (네트워크 오류 후 재시도 포함)
    // =========================================================================

    @Nested
    @DisplayName("에피소드 구매 — 멱등성")
    inner class Idempotence {

        @Test
        @DisplayName("이미 구매한 에피소드 재요청 시 isNew=false를 반환한다")
        fun `이미 구매한 에피소드 재요청 시 isNew=false를 반환한다`() {
            // Arrange
            purchaseService.purchase(user.id, episode.id)

            // Act
            val response = purchaseService.purchase(user.id, episode.id)

            // Assert
            assertThat(response.isNew).isFalse()
        }

        @Test
        @DisplayName("이미 구매한 에피소드 재요청 시 동일한 Purchase ID를 반환한다")
        fun `이미 구매한 에피소드 재요청 시 동일한 Purchase ID를 반환한다`() {
            // Arrange
            val first = purchaseService.purchase(user.id, episode.id)

            // Act
            val second = purchaseService.purchase(user.id, episode.id)

            // Assert
            assertThat(second.purchaseId).isEqualTo(first.purchaseId)
        }

        @Test
        @DisplayName("이미 구매한 에피소드 재요청 시 코인이 추가 차감되지 않는다")
        fun `이미 구매한 에피소드 재요청 시 코인이 추가 차감되지 않는다`() {
            // Arrange
            purchaseService.purchase(user.id, episode.id)
            val balanceAfterFirstPurchase = userRepository.findById(user.id).get().coinBalance

            // Act
            purchaseService.purchase(user.id, episode.id)

            // Assert
            val balanceAfterSecondPurchase = userRepository.findById(user.id).get().coinBalance
            assertThat(balanceAfterSecondPurchase).isEqualTo(balanceAfterFirstPurchase)
        }

        @Test
        @DisplayName("이미 구매한 에피소드 재요청 시 Purchase 레코드가 추가 생성되지 않는다")
        fun `이미 구매한 에피소드 재요청 시 Purchase 레코드가 추가 생성되지 않는다`() {
            // Arrange
            purchaseService.purchase(user.id, episode.id)

            // Act
            purchaseService.purchase(user.id, episode.id)

            // Assert
            assertThat(purchaseRepository.findAll()).hasSize(1)
        }

        @Test
        @DisplayName("동일 에피소드를 10회 연속 요청해도 Purchase는 1건이다")
        fun `동일 에피소드를 10회 연속 요청해도 Purchase는 1건이다`() {
            // Act
            repeat(10) { purchaseService.purchase(user.id, episode.id) }

            // Assert
            assertThat(purchaseRepository.findAll()).hasSize(1)
            assertThat(userEpisodeAccessRepository.findAll()).hasSize(1)
        }
    }

    // =========================================================================
    // 예외 처리
    // =========================================================================

    @Nested
    @DisplayName("에피소드 구매 — 예외 처리")
    inner class ExceptionHandling {

        @Test
        @DisplayName("존재하지 않는 사용자가 구매하면 USER_NOT_FOUND 예외가 발생한다")
        fun `존재하지 않는 사용자가 구매하면 USER_NOT_FOUND 예외가 발생한다`() {
            // Arrange
            val nonExistentUserId = 999L

            // Act & Assert
            assertThatThrownBy { purchaseService.purchase(nonExistentUserId, episode.id) }
                .isInstanceOf(WebtoonException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND)
        }

        @Test
        @DisplayName("존재하지 않는 에피소드를 구매하면 EPISODE_NOT_FOUND 예외가 발생한다")
        fun `존재하지 않는 에피소드를 구매하면 EPISODE_NOT_FOUND 예외가 발생한다`() {
            // Arrange
            val nonExistentEpisodeId = 999L

            // Act & Assert
            assertThatThrownBy { purchaseService.purchase(user.id, nonExistentEpisodeId) }
                .isInstanceOf(WebtoonException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EPISODE_NOT_FOUND)
        }

        @Test
        @DisplayName("코인이 부족하면 INSUFFICIENT_COINS 예외가 발생한다")
        fun `코인이 부족하면 INSUFFICIENT_COINS 예외가 발생한다`() {
            // Arrange
            val poorUser = userRepository.save(User(username = "빈곤 유저", email = "poor@test.com", coinBalance = 50))
            val expensiveEpisode = episodeRepository.save(Episode(title = "비싼 에피소드", price = 100))

            // Act & Assert
            assertThatThrownBy { purchaseService.purchase(poorUser.id, expensiveEpisode.id) }
                .isInstanceOf(WebtoonException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INSUFFICIENT_COINS)
        }

        @Test
        @DisplayName("코인이 부족하면 구매 실패 후 Purchase 레코드가 생성되지 않는다")
        fun `코인이 부족하면 구매 실패 후 Purchase 레코드가 생성되지 않는다`() {
            // Arrange
            val poorUser = userRepository.save(User(username = "빈곤 유저2", email = "poor2@test.com", coinBalance = 50))
            val expensiveEpisode = episodeRepository.save(Episode(title = "비싼 에피소드2", price = 100))

            // Act
            runCatching { purchaseService.purchase(poorUser.id, expensiveEpisode.id) }

            // Assert
            assertThat(purchaseRepository.findByUserIdAndEpisodeId(poorUser.id, expensiveEpisode.id)).isNull()
        }

        @Test
        @DisplayName("코인이 부족하면 구매 실패 후 UserEpisodeAccess가 생성되지 않는다")
        fun `코인이 부족하면 구매 실패 후 UserEpisodeAccess가 생성되지 않는다`() {
            // Arrange
            val poorUser = userRepository.save(User(username = "빈곤 유저3", email = "poor3@test.com", coinBalance = 50))
            val expensiveEpisode = episodeRepository.save(Episode(title = "비싼 에피소드3", price = 100))

            // Act
            runCatching { purchaseService.purchase(poorUser.id, expensiveEpisode.id) }

            // Assert
            assertThat(userEpisodeAccessRepository.findByUserIdAndEpisodeId(poorUser.id, expensiveEpisode.id)).isNull()
        }

        @Test
        @DisplayName("코인이 부족하면 구매 실패 후 코인 잔액이 변동되지 않는다")
        fun `코인이 부족하면 구매 실패 후 코인 잔액이 변동되지 않는다`() {
            // Arrange
            val poorUser = userRepository.save(User(username = "빈곤 유저4", email = "poor4@test.com", coinBalance = 50))
            val expensiveEpisode = episodeRepository.save(Episode(title = "비싼 에피소드4", price = 100))

            // Act
            runCatching { purchaseService.purchase(poorUser.id, expensiveEpisode.id) }

            // Assert
            val unchanged = userRepository.findById(poorUser.id).get()
            assertThat(unchanged.coinBalance).isEqualTo(50)
        }
    }

    // =========================================================================
    // 원자성: Purchase와 Access는 항상 같이 생성되거나 같이 롤백된다
    // =========================================================================

    @Nested
    @DisplayName("에피소드 구매 — 원자성")
    inner class Atomicity {

        @Test
        fun `구매 실패 시 Purchase와 Access가 함께 롤백된다`() {
            // Arrange
            val brokeUser = userRepository.save(User(username = "파산 유저", email = "broke@test.com", coinBalance = 0))

            // Act
            runCatching { purchaseService.purchase(brokeUser.id, episode.id) }

            // Assert
            val hasPurchase = purchaseRepository.findByUserIdAndEpisodeId(brokeUser.id, episode.id) != null
            val hasAccess = userEpisodeAccessRepository.findByUserIdAndEpisodeId(brokeUser.id, episode.id) != null
            assertThat(hasPurchase).isEqualTo(hasAccess) // 둘 다 없어야 함
        }
    }

    // =========================================================================
    // 열람 권한 조회
    // =========================================================================

    @Nested
    @DisplayName("열람 권한 조회")
    inner class 열람_권한_조회 {

        @Test
        @DisplayName("구매한 에피소드는 hasAccess=true를 반환한다")
        fun `구매한 에피소드는 hasAccess=true를 반환한다`() {
            // Arrange
            purchaseService.purchase(user.id, episode.id)

            // Act
            val response = purchaseService.getAccess(user.id, episode.id)

            // Assert
            assertThat(response.hasAccess).isTrue()
        }

        @Test
        @DisplayName("구매하지 않은 에피소드 열람 시 ACCESS_NOT_FOUND 예외가 발생한다")
        fun `구매하지 않은 에피소드 열람 시 ACCESS_NOT_FOUND 예외가 발생한다`() {
            // Act & Assert
            assertThatThrownBy { purchaseService.getAccess(user.id, episode.id) }
                .isInstanceOf(WebtoonException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCESS_NOT_FOUND)
        }

        @Test
        @DisplayName("존재하지 않는 사용자 열람 조회 시 USER_NOT_FOUND 예외가 발생한다")
        fun `존재하지 않는 사용자 열람 조회 시 USER_NOT_FOUND 예외가 발생한다`() {
            // Act & Assert
            assertThatThrownBy { purchaseService.getAccess(999L, episode.id) }
                .isInstanceOf(WebtoonException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND)
        }

        @Test
        @DisplayName("존재하지 않는 에피소드 열람 조회 시 EPISODE_NOT_FOUND 예외가 발생한다")
        fun `존재하지 않는 에피소드 열람 조회 시 EPISODE_NOT_FOUND 예외가 발생한다`() {
            // Act & Assert
            assertThatThrownBy { purchaseService.getAccess(user.id, 999L) }
                .isInstanceOf(WebtoonException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EPISODE_NOT_FOUND)
        }
    }
}
