package com.vivo.lpa

/** Negative-only validation for model-authored retrieval phrases. It never invents a query. */
object RetrievalQueryGuard {
    fun canonicalize(original: String, candidate: String): String? {
        if (!isUsable(original, candidate)) return null
        val core = semanticCore(candidate)
        if (core.isNotBlank()) return core
        val lowerOriginal = original.lowercase()
        val lowerCandidate = candidate.lowercase()
        return listOf("live photo", "动态照片", "视频", "video", "图片", "照片")
            .firstOrNull { it in lowerOriginal && it in lowerCandidate }
    }

    fun isUsable(original: String, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val originalCore = semanticCore(original)
        val candidateCore = semanticCore(candidate)
        if (originalCore.isBlank()) {
            val lowerOriginal = original.lowercase()
            val lowerCandidate = candidate.lowercase()
            return listOf("视频", "video", "live photo", "动态照片", "图片", "照片")
                .any { it in lowerOriginal && it in lowerCandidate }
        }
        if (candidateCore.isBlank()) return false

        val originalHan = originalCore.filter { it.code in 0x4e00..0x9fff }.toSet()
        val candidateHan = candidateCore.filter { it.code in 0x4e00..0x9fff }.toSet()
        if (originalHan.isNotEmpty() && candidateHan.isNotEmpty() &&
            originalHan.intersect(candidateHan).isEmpty()
        ) return false

        val originalLatin = originalCore.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }.toSet()
        val candidateLatin = candidateCore.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }.toSet()
        if (originalLatin.isNotEmpty() && candidateLatin.isNotEmpty() &&
            originalLatin.intersect(candidateLatin).isEmpty()
        ) return false
        return true
    }

    fun semanticCore(text: String): String {
        var value = text.lowercase()
        val noise = listOf(
            "live photo", "livephoto", "动态照片", "实况照片", "本地", "相册", "最近", "所有",
            "包含", "相关", "素材", "图片", "照片", "视频", "给我", "我的", "这张", "这段", "一张",
            "一段", "几张", "画面", "一点", "找", "寻找", "搜索", "选择", "做个", "做成", "制作",
            "调成", "变成", "加个", "弄成", "处理", "三拼", "四拼",
            "拼图", "拼接", "拼贴", "模板", "版式", "滤镜", "调色", "黑白", "暖色", "冷色",
            "复古", "鲜艳", "倒放", "变速", "加速", "慢放", "裁剪", "镜像", "翻转", "导出",
            "发布", "collage", "compose", "template", "filter", "reverse", "trim", "export",
            "photos", "photo", "album", "all", "from", "find", "search", "video", "image", "asset",
            "make", "create"
        )
        noise.sortedByDescending(String::length).forEach { value = value.replace(it, " ") }
        value = value.replace(Regex("[一二两三四五六七八九0-9]+\\s*拼"), " ")
        value = value.replace(Regex("[的把将请和或并再后前中里用从个]+"), " ")
        return value.replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), " ").trim()
    }
}
