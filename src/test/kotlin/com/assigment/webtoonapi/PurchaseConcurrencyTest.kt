package com.assigment.webtoonapi

import com.assigment.webtoonapi.domain.Episode
import com.assigment.webtoonapi.domain.User
import com.assigment.webtoonapi.repository.*
import com.assigment.webtoonapi.service.PurchaseService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PurchaseConcurrencyTest {

    companion object {
        @Container
        @JvmStatic
        val redisContainer = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

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

    private lateinit var episode: Episode

    @BeforeEach
    fun setUp() {
        episode = episodeRepository.save(
            Episode(title = "동시성 테스트 에피소드", price = 100)
        )
    }

    @AfterEach
    fun tearDown() {
        userEpisodeAccessRepository.deleteAll()
        purchaseRepository.deleteAll()
        userRepository.deleteAll()
        episodeRepository.deleteAll()
    }

    // =========================================================
    // 핵심: 현실적인 동시성 실행 유틸
    // =========================================================
    private fun executeConcurrently(
        totalRequests: Int,
        concurrency: Int,
        action: (Int) -> Unit
    ): ConcurrencyResult {

        val executor = Executors.newFixedThreadPool(concurrency)
        val startBarrier = CyclicBarrier(concurrency)
        val doneLatch = CountDownLatch(totalRequests)

        val success = AtomicInteger(0)
        val fail = AtomicInteger(0)

        repeat(totalRequests) { index ->
            executor.submit {
                try {
                    startBarrier.await() // 진짜 동시 시작
                    action(index)
                    success.incrementAndGet()
                } catch (e: Exception) {
                    fail.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        doneLatch.await(60, TimeUnit.SECONDS)
        executor.shutdown()

        return ConcurrencyResult(success.get(), fail.get())
    }

    private data class ConcurrencyResult(val success: Int, val fail: Int)

    // =========================================================
    // 동일 사용자 테스트
    // =========================================================
    @Nested
    inner class `동일 사용자 + 동일 에피소드` {

        private lateinit var user: User

        @BeforeEach
        fun init() {
            user = userRepository.save(
                User(
                    username = "tester",
                    email = "same_${System.nanoTime()}@test.com",
                    coinBalance = 10_000
                )
            )
        }

        @Test
        fun `1000 요청 - 동시성 50 → Purchase 1건`() {
            val result = executeConcurrently(
                totalRequests = 1000,
                concurrency = 50
            ) {
                purchaseService.purchase(user.id, episode.id)
            }

            val purchases = purchaseRepository.findAll()
                .filter { it.user.id == user.id && it.episode.id == episode.id }

            assertThat(purchases).hasSize(1)
            assertThat(result.fail).isEqualTo(0)
        }

        @Test
        fun `코인 1회만 차감`() {
            val before = user.coinBalance

            executeConcurrently(1000, 50) {
                purchaseService.purchase(user.id, episode.id)
            }

            val after = userRepository.findById(user.id).get().coinBalance

            assertThat(after).isEqualTo(before - episode.price)
        }

        @Test
        fun `Access 1건만 생성`() {
            executeConcurrently(1000, 50) {
                purchaseService.purchase(user.id, episode.id)
            }

            val access = userEpisodeAccessRepository.findAll()
                .filter { it.user.id == user.id && it.episode.id == episode.id }

            assertThat(access).hasSize(1)
        }
    }

    // =========================================================
    // 서로 다른 사용자 테스트
    // =========================================================
    @Nested
    @DisplayName("다른 사용자 병렬 처리")
    inner class MultiUserTest {

        @Test
        fun `1000명 요청 - 동시성 100 → 모두 성공`() {
            val users = createUsers(1000)

            val result = executeConcurrently(1000, 100) { i ->
                purchaseService.purchase(users[i].id, episode.id)
            }

            assertThat(result.success).isEqualTo(1000)
            assertThat(result.fail).isEqualTo(0)
        }

        @Test
        fun `각 사용자별 Purchase 생성`() {
            val users = createUsers(1000)

            executeConcurrently(1000, 100) { i ->
                purchaseService.purchase(users[i].id, episode.id)
            }

            val purchases = purchaseRepository.findAll()
                .filter { it.episode.id == episode.id }

            assertThat(purchases).hasSize(1000)
        }

        @Test
        fun `각 사용자별 Access 생성`() {
            val users = createUsers(1000)

            executeConcurrently(1000, 100) { i ->
                purchaseService.purchase(users[i].id, episode.id)
            }

            val accesses = userEpisodeAccessRepository.findAll()
                .filter { it.episode.id == episode.id }

            assertThat(accesses).hasSize(1000)
        }
    }

    // =========================================================
    // 동일 사용자 + 다른 에피소드
    // Redis 락은 에피소드 단위라 병렬 진행되고,
    // DB 비관적 락(FOR UPDATE)이 coinBalance 경합을 직렬화한다
    // =========================================================
    @Nested
    @DisplayName("동일 사용자 + 다른 에피소드")
    inner class `동일 사용자 + 다른 에피소드` {

        @Test
        fun `N개 에피소드 동시 구매 시 coinBalance가 N×price 만큼 정확히 차감된다`() {
            val episodeCount = 10
            val price = 100
            val episodes = (1..episodeCount).map {
                episodeRepository.save(Episode(title = "에피소드$it", price = price))
            }
            val user = userRepository.save(
                User(username = "multi_buyer", email = "multi_${System.nanoTime()}@test.com", coinBalance = episodeCount * price)
            )

            executeConcurrently(episodeCount, episodeCount) { i ->
                purchaseService.purchase(user.id, episodes[i].id)
            }

            val balance = userRepository.findById(user.id).get().coinBalance
            assertThat(balance).isEqualTo(0)
        }

        @Test
        fun `N개 에피소드 동시 구매 시 각 에피소드별 Purchase가 1건씩 생성된다`() {
            val episodeCount = 10
            val episodes = (1..episodeCount).map {
                episodeRepository.save(Episode(title = "에피소드$it", price = 100))
            }
            val user = userRepository.save(
                User(username = "multi_buyer2", email = "multi2_${System.nanoTime()}@test.com", coinBalance = episodeCount * 100)
            )

            executeConcurrently(episodeCount, episodeCount) { i ->
                purchaseService.purchase(user.id, episodes[i].id)
            }

            episodes.forEach { ep ->
                assertThat(purchaseRepository.findByUserIdAndEpisodeId(user.id, ep.id)).isNotNull
            }
        }

        @Test
        fun `N개 에피소드 동시 구매 시 각 에피소드별 Access가 1건씩 생성된다`() {
            val episodeCount = 10
            val episodes = (1..episodeCount).map {
                episodeRepository.save(Episode(title = "에피소드$it", price = 100))
            }
            val user = userRepository.save(
                User(username = "multi_buyer3", email = "multi3_${System.nanoTime()}@test.com", coinBalance = episodeCount * 100)
            )

            executeConcurrently(episodeCount, episodeCount) { i ->
                purchaseService.purchase(user.id, episodes[i].id)
            }

            episodes.forEach { ep ->
                assertThat(userEpisodeAccessRepository.findByUserIdAndEpisodeId(user.id, ep.id)).isNotNull
            }
        }

        @Test
        fun `잔액이 부족하면 동시 구매 요청 중 하나만 성공하고 coinBalance는 음수가 되지 않는다`() {
            // 잔액 100, 에피소드 가격 100 × 2 → 1개만 성공해야 함
            val ep1 = episodeRepository.save(Episode(title = "에피소드A", price = 100))
            val ep2 = episodeRepository.save(Episode(title = "에피소드B", price = 100))
            val limitedUser = userRepository.save(
                User(username = "limited", email = "limited_${System.nanoTime()}@test.com", coinBalance = 100)
            )

            val result = executeConcurrently(2, 2) { i ->
                val ep = if (i == 0) ep1 else ep2
                purchaseService.purchase(limitedUser.id, ep.id)
            }

            val purchases = purchaseRepository.findAll().filter { it.user.id == limitedUser.id }
            val balance = userRepository.findById(limitedUser.id).get().coinBalance

            assertThat(result.success).isEqualTo(1)
            assertThat(result.fail).isEqualTo(1)
            assertThat(purchases).hasSize(1)
            assertThat(balance).isEqualTo(0)
        }
    }

    // =========================================================
    // 데이터 정합성
    // =========================================================
    @Nested
    @DisplayName("데이터 정합성")
    inner class ConsistencyTest {

        @Test
        fun coin_should_not_be_negative() {
            val user = userRepository.save(
                User(
                    username = "edge",
                    email = "edge_${System.nanoTime()}@test.com",
                    coinBalance = 100
                )
            )

            executeConcurrently(100, 20) {
                purchaseService.purchase(user.id, episode.id)
            }

            val balance = userRepository.findById(user.id).get().coinBalance

            assertThat(balance).isGreaterThanOrEqualTo(0)
        }

        @Test
        fun purchase_and_access_should_be_atomic() {
            val users = createUsers(200)

            executeConcurrently(200, 50) { i ->
                purchaseService.purchase(users[i].id, episode.id)
            }

            users.forEach { u ->
                val hasPurchase = purchaseRepository.findByUserIdAndEpisodeId(u.id, episode.id) != null
                val hasAccess = userEpisodeAccessRepository.findByUserIdAndEpisodeId(u.id, episode.id) != null

                assertThat(hasPurchase).isEqualTo(hasAccess)
            }
        }
    }

    // =========================================================
    // 유틸
    // =========================================================
    private fun createUsers(count: Int): List<User> {
        return (0 until count).map { i ->
            userRepository.save(
                User(
                    username = "user$i",
                    email = "diff_${System.nanoTime()}_$i@test.com",
                    coinBalance = 1000
                )
            )
        }
    }
}