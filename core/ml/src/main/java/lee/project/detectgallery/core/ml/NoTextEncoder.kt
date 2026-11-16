package lee.project.detectgallery.core.ml

import lee.project.detectgallery.core.domain.port.TextEncoder
import javax.inject.Inject

/** CLIP 도입 전 자리. 문장 검색 경로를 끈다. */
class NoTextEncoder @Inject constructor() : TextEncoder {
    override suspend fun encode(text: String): FloatArray? = null
}