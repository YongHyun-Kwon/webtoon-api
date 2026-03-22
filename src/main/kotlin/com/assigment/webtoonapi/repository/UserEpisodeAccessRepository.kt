package com.assigment.webtoonapi.repository

import com.assigment.webtoonapi.domain.UserEpisodeAccess
import org.springframework.data.jpa.repository.JpaRepository

interface UserEpisodeAccessRepository : JpaRepository<UserEpisodeAccess, Long> {

    fun findByUserIdAndEpisodeId(userId: Long, episodeId: Long): UserEpisodeAccess?
}
