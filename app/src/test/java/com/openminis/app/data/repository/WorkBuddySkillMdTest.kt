package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkBuddySkillMdTest {

    @Test
    fun readsDisplayNameAndChineseDescription() {
        val md = """
---
name: ecom-visual
display_name: 电商视觉主图
description_zh: 生成电商主图、详情页与社媒配图
description_en: Generate e-commerce hero images
version: 1.2.0
---

正文步骤
""".trimIndent()
        val parsed = SkillRepository.parseSkillMd(md)!!
        assertEquals("ecom-visual", parsed.name)
        assertEquals("生成电商主图、详情页与社媒配图", parsed.description)
        assertEquals("1.2.0", parsed.version)
    }

    @Test
    fun fallsBackToDisplayNameWhenNameMissing() {
        val md = """
---
display_name: 海报生成
description: 生成活动海报
---

步骤
""".trimIndent()
        val parsed = SkillRepository.parseSkillMd(md)!!
        assertEquals("海报生成", parsed.name)
        assertEquals("生成活动海报", parsed.description)
    }
}
