package com.vivo.lpa

/** Literal request facts are runtime constraints, not model-authored semantic choices. */
object RequestFactExtractor {
    private val numberWords = mapOf(
        "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
        "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9
    )

    private val countPattern = Regex(
        "(?i)([1-9]|一|二|两|三|四|五|六|七|八|九|one|two|three|four|five|six|seven|eight|nine)" +
            "\\s*(?:张|个|段|条|幅|份|拼|photo(?:s)?|image(?:s)?|asset(?:s)?|video(?:s)?|clip(?:s)?|collage)"
    )

    fun explicitCount(query: String): Int? {
        val token = countPattern.find(query)?.groupValues?.getOrNull(1)?.lowercase()
            ?: return null
        return numberWords[token] ?: token.toIntOrNull()
    }

    fun explicitMedia(query: String): RuntimeMediaKind? {
        val lower = query.lowercase()
        return when {
            listOf("live photo", "livephoto", "动态照片", "实况照片").any(lower::contains) ->
                RuntimeMediaKind.LIVE_PHOTO
            listOf("视频", "video", "clip").any(lower::contains) -> RuntimeMediaKind.VIDEO
            listOf("普通图片", "静态图片", "静态照片", "jpg", "jpeg", "png", "still image", "still photo")
                .any(lower::contains) -> RuntimeMediaKind.IMAGE
            else -> null
        }
    }
}
