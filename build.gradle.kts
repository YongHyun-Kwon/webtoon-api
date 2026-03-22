plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
    id("org.springframework.boot") version "3.5.12"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.assigment"
version = "0.0.1-SNAPSHOT"
description = "webtoon-api"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Redis 분산 락 (Redisson: 분산 락에 특화된 Redis 클라이언트)
    implementation("org.redisson:redisson-spring-boot-starter:3.27.2")

    // Swagger / OpenAPI 문서화
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")

    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testcontainers: 테스트 환경에서 실제 Redis 컨테이너 실행
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    // GenericContainer가 JUnit 4 TestRule을 구현하므로 컴파일 시 필요
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// JPA Entity에서 기본 생성자 자동 생성 (Hibernate 요구사항)
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
