package com.assigment.webtoonapi.repository

import com.assigment.webtoonapi.domain.Episode
import org.springframework.data.jpa.repository.JpaRepository

interface EpisodeRepository : JpaRepository<Episode, Long>
