package com.assigment.webtoonapi.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.Contact
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Webtoon API")
                .description(
                    """
                    웹툰 에피소드 구매 및 열람 권한 관리 API

                    ## 주요 기능
                    - **에피소드 구매**: 코인을 사용해 에피소드를 구매합니다. 멱등성을 보장하여 중복 요청을 안전하게 처리합니다.
                    - **열람 권한 조회**: 구매한 에피소드의 열람 권한을 확인합니다.

                    ## 동시성 처리
                    - Redis 분산 락 + DB 비관적 락 + DB UNIQUE 제약으로 3중 보호
                    - 동일 사용자의 동시 중복 구매 방지
                    """.trimIndent()
                )
                .version("1.0.0")
                .contact(
                    Contact()
                        .name("Webtoon API Team")
                )
        )
}