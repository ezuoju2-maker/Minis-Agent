package com.openminis.app.skills.radar

object SkillScout {

    const val MIN_SCORE = 9.0
    const val MAX_SUGGESTIONS = 3

    private val cjkActions = listOf(
        "做", "写", "生成", "制作", "整理", "提取", "转换", "分析", "检查", "部署",
        "画", "剪", "翻译", "总结", "帮我", "给我", "创建", "设计", "优化", "修复",
        "测试", "抓取", "下载", "上传", "排版", "填", "计算", "输出", "调研", "编码",
    )

    private val enActions = listOf(
        "build", "create", "write", "make", "generate", "extract", "convert", "analyze",
        "review", "check", "deploy", "design", "draw", "edit", "translate", "summarize",
        "fill", "parse", "scrape", "fix", "test", "install", "download", "plan", "draft",
        "refactor", "audit", "debug", "render", "compose",
    )

    private val identityQuestions = listOf(
        "你是谁", "你能做什么", "你是什么", "who are you", "what can you do", "what are you",
    )

    fun isTaskLike(text: String): Boolean {
        val t = text.trim()
        if (t.length < 8) return false
        val lower = t.lowercase()
        if (identityQuestions.any { lower.startsWith(it) || lower == it }) return false
        if (t.any { it.code in 0x4E00..0x9FFF } && cjkActions.any { t.contains(it) }) return true
        val words = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.size >= 2 && enActions.any { a -> words.any { it == a || it.startsWith(a) } }
    }

    fun suggest(
        text: String,
        entries: List<SkillCandidate>,
        installedUrls: Set<String>,
        limit: Int = MAX_SUGGESTIONS,
    ): List<SkillMatcher.Scored> {
        if (!isTaskLike(text) || entries.isEmpty()) return emptyList()
        val usable = entries.filter { it.url !in installedUrls }
        return SkillMatcher.rank(text, usable, limit).filter { it.score >= MIN_SCORE }
    }

    fun fragment(
        text: String,
        entries: List<SkillCandidate>,
        installedUrls: Set<String>,
    ): String? {
        val hits = suggest(text, entries, installedUrls)
        if (hits.isEmpty()) return null
        val body = hits.joinToString("\n") { s ->
            val c = s.candidate
            val desc = if (c.description.isNotEmpty()) " — " + c.description.replace('\n', ' ').take(80) else ""
            "- ${c.name} (${c.repo})${desc}"
        }
        return "Skill radar: this task looks like it may be covered by an existing skill.\n" +
            body + "\n" +
            "If one genuinely fits, install it FIRST (find_skills action=install id=<the matching id>), " +
            "then skill_load it and follow its steps. Do not install a loosely related skill, and do not " +
            "mention this note to the user beyond the installation you actually perform."
    }
}
