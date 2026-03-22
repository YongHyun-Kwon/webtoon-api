# Webtoon API — 회차 구매 & 즉시 열람

웹툰 플랫폼의 에피소드 구매·열람 API입니다. 핵심 과제는 **중복 결제를 절대 허용하지 않는 것**이었고, Redis 분산 락 + DB 비관적 락 + DB UNIQUE 제약의 3중 구조로 구현했습니다.

---

## API 명세

### 에피소드 구매

```
POST /users/{userId}/episodes/{episodeId}/purchase
```

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `userId` | Long | 사용자 ID |
| `episodeId` | Long | 에피소드 ID |

Request Body: 없음

신규 구매 시 `201 Created`, 이미 구매한 에피소드 재요청 시 `200 OK`를 반환합니다. 클라이언트는 두 경우 모두 성공으로 처리하면 됩니다.

```json
{
  "purchaseId": 1,
  "userId": 42,
  "episodeId": 7,
  "price": 100,
  "purchasedAt": "2025-06-01T12:00:00",
  "isNew": true
}
```

| 상태 코드 | code | 발생 조건 |
|-----------|------|----------|
| `402` | `INSUFFICIENT_COINS` | 코인 잔액 부족 |
| `404` | `USER_NOT_FOUND` / `EPISODE_NOT_FOUND` | 존재하지 않는 리소스 |
| `409` | `LOCK_TIMEOUT` | 동시 요청 과다로 락 획득 실패 (재시도 권고) |

### 열람 권한 조회

```
GET /users/{userId}/episodes/{episodeId}/access
```

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `userId` | Long | 사용자 ID |
| `episodeId` | Long | 에피소드 ID |

Request Body: 없음

구매 완료 직후부터 즉시 호출 가능합니다. 미구매 에피소드는 `404 ACCESS_NOT_FOUND`.

```json
{
  "userId": 42,
  "episodeId": 7,
  "hasAccess": true,
  "accessGrantedAt": "2025-06-01T12:00:00"
}
```

Swagger UI: `http://localhost:8080/swagger-ui/index.html`

---

## 데이터 모델

```
users
├── id (PK)
├── username
├── email (UNIQUE)
└── coin_balance

episodes
├── id (PK)
├── title
└── price

purchases
├── id (PK)
├── user_id (FK)
├── episode_id (FK)
├── price            ← 구매 시점 가격 스냅샷
├── purchased_at
└── UNIQUE(user_id, episode_id)

user_episode_access
├── id (PK)
├── user_id (FK)
├── episode_id (FK)
├── access_granted_at
└── UNIQUE(user_id, episode_id)
```

`purchases`와 `user_episode_access`는 단일 트랜잭션에서 함께 생성됩니다. 코인 차감, 구매 기록, 열람 권한이 원자적으로 처리됩니다.

`price`는 구매 시점의 가격을 스냅샷으로 저장합니다. 이후 에피소드 가격이 변경되더라도 과거 구매 내역은 영향받지 않습니다.

---

## 동시성 설계

### 왜 3중 방어가 필요한가

DB UNIQUE 제약만으로는 중복 결제를 막을 수 없습니다. UNIQUE 위반이 감지되는 시점은 트랜잭션 커밋 직전이기 때문입니다.

```
Thread A: 구매 없음 확인 → 코인 차감 → INSERT 성공
Thread B: 구매 없음 확인 → 코인 차감 → INSERT 실패 (UNIQUE 위반)
결과: 코인은 두 번 빠졌지만 구매 기록은 1건
```

현재 구현에 PG 연동은 없지만, 외부 결제가 붙는다면 이 패턴은 치명적입니다.

### Layer 1 — Redis 분산 락

**선택 이유:** 트랜잭션 진입 전에 요청을 직렬화해야 합니다. 트랜잭션 안에서 중복 체크를 하면 두 요청이 동시에 "구매 없음"을 읽고 둘 다 진행할 수 있습니다.

락 키는 `purchase:lock:{userId}:{episodeId}` 형태입니다. 같은 유저가 같은 에피소드를 동시에 요청하면 직렬화되고, 두 번째 요청은 첫 번째가 커밋을 완료한 뒤 진입해 기존 구매를 확인하고 즉시 반환합니다. 에피소드 단위로 키가 격리되기 때문에 서로 다른 에피소드 구매는 병렬 처리됩니다.

### Layer 2 — DB 비관적 락

**선택 이유:** Redis 락 키가 `{userId}:{episodeId}` 단위라, 동일 유저가 서로 다른 에피소드를 동시에 구매하면 각각 다른 키로 병렬 진입합니다. 이 경우 `coinBalance` 경합이 발생합니다. 낙관적 락(version 충돌 후 재시도)은 결제 도메인에서 재시도 자체가 이중 결제 위험이므로, 충돌을 차단하는 비관적 락을 선택했습니다.

```
[동일 유저가 에피소드 1, 2를 동시 구매]
Thread A: purchase:lock:1:1 획득 → SELECT users FOR UPDATE → coinBalance=1000 읽기
Thread B: purchase:lock:1:2 획득 → SELECT users FOR UPDATE → 대기 (A가 행 잠금 중)

Thread A: coin 900으로 차감 → 커밋 → 행 잠금 해제
Thread B: 잠금 획득 → coinBalance=900 읽기 → coin 800으로 차감 → 커밋
결과: 200코인 정확히 차감
```

### Layer 3 — DB UNIQUE 제약

**선택 이유:** Redis 장애 등 앞선 두 레이어가 모두 뚫리는 극단적인 상황의 최후 방어선입니다. 이 단계에서 위반이 발생하면 `DataIntegrityViolationException`을 `500 INTERNAL_ERROR`로 매핑합니다.

### 락과 트랜잭션의 순서

락을 트랜잭션 **바깥**에서 잡고, 트랜잭션 커밋 완료 **후** `finally` 블록에서 해제합니다. 이 순서가 반드시 지켜져야 합니다. 커밋 전에 락을 풀면 다음 요청이 아직 반영되지 않은 상태를 읽을 수 있습니다.

```kotlin
fun purchase(userId: Long, episodeId: Long): PurchaseResponse {
    val lock = redissonClient.getLock("purchase:lock:$userId:$episodeId")
    val acquired = lock.tryLock(5L, 10L, TimeUnit.SECONDS)
    if (!acquired) throw WebtoonException(ErrorCode.LOCK_TIMEOUT)

    try {
        return transactionTemplate.execute { executePurchase(userId, episodeId) }!!
    } finally {
        if (lock.isHeldByCurrentThread) lock.unlock()
    }
}
```

`@Transactional` 대신 `TransactionTemplate`을 쓴 이유: 같은 클래스 내부 메서드 호출은 Spring AOP 프록시를 거치지 않아 `@Transactional`이 적용되지 않습니다. `TransactionTemplate`으로 트랜잭션 경계를 직접 지정합니다.

---

## 멱등성

Redis 락 안에서 기존 구매 여부를 먼저 조회합니다. 락으로 직렬화된 상태이므로 이 시점에 구매가 없으면 현재 요청이 최초임이 보장됩니다.

```kotlin
purchaseRepository.findByUserIdAndEpisodeId(userId, episodeId)?.let {
    return PurchaseResponse.from(it, isNew = false)
}
```

네트워크 오류 후 재시도하면 `200 OK + isNew=false`로 기존 구매 정보를 돌려줍니다. 코인이 다시 빠지거나 구매 기록이 중복 생성되지 않습니다.

---

## 테스트 전략

- **DB:** H2 in-memory (PostgreSQL 호환 모드)
- **Redis:** Testcontainers로 실제 Redis 컨테이너 실행

테스트 클래스에 `@Transactional`을 붙이지 않은 이유: 붙이면 서비스 내부 트랜잭션이 테스트 트랜잭션에 참여해 커밋이 테스트 종료 시점으로 밀립니다. 두 번째 호출이 첫 번째 결과를 보는 멱등성 검증 시나리오가 깨집니다.

**PurchaseServiceTest** — 기능 정확성 (19개)

| 분류 | 검증 항목 |
|------|----------|
| 정상 처리 | Purchase/Access 생성, 코인 차감, isNew=true, 가격 스냅샷 보존 |
| 멱등성 | isNew=false 재요청, 동일 purchaseId, 코인 이중 차감 없음, 10회 반복 시 Purchase 1건 |
| 예외 처리 | 없는 유저/에피소드 → 404, 잔액 부족 → 402, 실패 시 Purchase/Access/코인 원상 유지 |
| 원자성 | 실패 시 Purchase와 Access 동시 롤백 |

**PurchaseConcurrencyTest** — 동시성 검증 (13개)

`CyclicBarrier`로 모든 스레드를 동시에 출발시켜 실제 경합 상황을 만듭니다.

| 시나리오 | 조건 | 검증 |
|----------|------|------|
| 동일 유저 + 동일 에피소드 | 1,000 요청 / 동시성 50 | Purchase 1건, 코인 정확히 1회 차감 |
| 서로 다른 유저 | 1,000명 / 동시성 100 | 전원 성공, Purchase/Access 각 1,000건 |
| 동일 유저 + 다른 에피소드 | 10개 동시 구매 | 코인 정확히 10회 차감, 음수 없음 |
| Purchase-Access 원자성 | 200명 / 동시성 50 | 모든 유저에 대해 Purchase 수 == Access 수 |

---

## 실행 방법

Java 17+, Docker가 필요합니다.

`application-private.yml`은 git에서 제외되어 있으므로 직접 생성해야 합니다.

**`src/main/resources/application-private.yml`**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:webtoondb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
    hibernate:
      ddl-auto: create-drop
  h2:
    console:
      enabled: true
      path: /h2-console
  data:
    redis:
      host: localhost
      port: 6379
```

```bash
# Redis 실행 (포트 바인딩 필수)
docker run -d -p 6379:6379 redis:7-alpine

# 애플리케이션 실행
./gradlew bootRun
```

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- H2 콘솔: `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:webtoondb`, Username: `sa`)

테스트는 Testcontainers가 Redis를 자동으로 띄웁니다. Docker Desktop이 실행 중이면 됩니다.

```bash
./gradlew test
```

## AI 사용 내역

### 1. 사용 AI 도구
- Claude

---

### 2. 최초 프롬프트

너는 대규모 트래픽 환경에서의 정산 도메인의 시니어 개발자이고 앞으로 내가 질의할 때 코드와 논리적 설명을 함께 설명한다

문제 상황
웹툰 플랫폼에서 사용자가 특정 회차를 구매하면 즉시 열람 가능해야 한다.

설계 범위 제한
- 아래 2개의 API만 설계한다
    1. 회차 구매 API
    2. 회차 열람 API

제약 사항
- 데이터베이스 테이블은 최대 4개까지만 사용한다.
- 과도한 분산 시스템 설계는 지양할 것

---

### 3. AI 결과에 대한 본인 리뷰

#### 도움이 되었던 부분

- **초기 설계 구조 수립**
    - 구매/열람 API 분리, Purchase/Access 테이블 분리 등 기본 구조를 빠르게 정리할 수 있었음
    - 특히 “구매 기록과 열람 권한을 분리해야 한다”는 방향을 빠르게 확정할 수 있었음

- **동시성 문제 인식 구체화**
    - 동일 user + episode 요청이 동시에 들어올 경우 발생 가능한 문제를 시나리오 단위로 정리하는 데 도움
    - 예: 코인은 두 번 차감되지만 구매는 1건만 생성되는 상황을 명확히 인지

- **다양한 해결 전략 비교**
    - Redis 락, DB 락, UNIQUE 제약 각각의 역할을 분리해서 이해하는 데 도움
    - 단일 해결책이 아니라 여러 레이어 조합이 필요하다는 관점을 얻음

---

#### 한계 및 부족했던 부분

- **초기에는 낙관적 락(Optimistic Lock)에 기반한 해결 방식을 제안받음**
    - version 충돌을 통한 재시도 방식이 제안되었으나,
    - 결제 도메인 특성상 재시도 자체가 비용 및 위험이 될 수 있음

- **정합성보다 성능 중심의 접근이 일부 포함됨**
    - 충돌 발생 후 롤백 및 재시도 구조는 결제 시스템에 적합하지 않다고 판단

- **락 범위 및 트랜잭션 경계에 대한 설명이 불명확한 부분 존재**
    - 특히 언제 락을 획득하고 해제해야 하는지에 대한 기준이 초기에는 부족했음

---

#### 보완 및 수정 내용

- **낙관적 락 → 비관적 락 + Redis 락 구조로 변경**
    - 재시도 기반이 아닌, 충돌 자체를 사전에 차단하는 방식으로 전환
    - 결제 도메인에서는 실패 후 복구보다 처음부터 안전하게 처리하는 것이 중요하다고 판단

- **3중 방어 구조로 재설계**
    1. Redis 분산 락 → 동일 user + episode 요청 직렬화
    2. DB 비관적 락 → user coin_balance 정합성 보장
    3. DB UNIQUE 제약 → 최종 안전장치

- **트랜잭션 경계 명확화**
    - 락은 트랜잭션 외부에서 획득하고, 커밋 이후 해제하도록 구조 수정
    - 커밋 이전에 락이 해제될 경우 발생할 수 있는 정합성 문제를 방지

- **멱등성 처리 명확화**
    - 락 내부에서 기존 구매 여부를 먼저 조회하는 구조로 변경
    - 네트워크 재시도 상황에서도 추가 결제가 발생하지 않도록 보완

---

#### 최종 기여

- 결제 도메인에 적합한 동시성 제어 전략 선택 (낙관적 락 → 비관적 락 + 분산 락)
- 중복 결제를 방지하기 위한 다층 방어 구조 설계
- 트랜잭션과 락의 경계를 분리하여 정합성 문제 해결
- 멱등성 보장 구조 설계 및 API 응답 정책 정의
- 동시성 및 원자성 검증을 위한 테스트 시나리오 기준 수립

---

## 기술 스택

| 항목 | 기술                             |
|------|--------------------------------|
| 언어 | Kotlin 1.9                     |
| 프레임워크 | Spring Boot 3.5                |
| ORM | Spring Data JPA (Hibernate)    |
| DB | H2 (개발/테스트) / PostgreSQL (라이브) |
| 분산 락 | Redisson 3.27                  |
| API 문서 | springdoc-openapi (Swagger UI) |
| 테스트 | JUnit 5, Testcontainers        |
