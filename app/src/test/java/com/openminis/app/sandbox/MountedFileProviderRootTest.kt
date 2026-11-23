package com.openminis.app.sandbox

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MountedFileProviderRootTest {

    private val xml = File("src/main/res/xml/file_provider_paths.xml")

    private fun storageRoots(): List<Pair<String, String>> {
        assertTrue("missing ${xml.absolutePath}", xml.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length).mapNotNull { i ->
            val el = nodes.item(i) as? Element ?: return@mapNotNull null
            val path = el.getAttribute("path").trim('/')
            val base = when (el.tagName) {
                "root-path" -> ""
                "external-path" -> "/storage/emulated/0"
                else -> return@mapNotNull null
            }
            el.tagName to (if (path.isEmpty()) base.ifEmpty { "/" } else "$base/$path")
        }
    }

    private fun covered(file: String) = storageRoots().any { (_, dir) ->
        dir == "/" || file == dir || file.startsWith("$dir/")
    }

    @Test
    fun `a file in a mounted folder on internal storage has a provider root`() {
        assertTrue(covered("/storage/emulated/0/agents/meituan/益丰大药房_搜索页_商品清单.xlsx"))
    }

    @Test
    fun `a file in a mounted folder on an SD card has a provider root`() {
        assertTrue(covered("/storage/1A2B-3C4D/Documents/report.pdf"))
    }

    @Test
    fun `the storage root is scoped to storage, not the whole filesystem`() {
        val roots = storageRoots()
        assertFalse("a root-path of \"/\" would expose every path on the device", roots.any { it.second == "/" })
        assertFalse(covered("/data/data/com.other.app/databases/x.db"))
        assertFalse(covered("/system/etc/hosts"))
    }

    @Test
    fun `open externally no longer reports every failure as a missing viewer app`() {
        val src = File("src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt").readText()
        val body = src.substringAfter("private fun openExternally(").substringBefore("\n}\n")
        assertTrue(
            "only a real ActivityNotFoundException may say no app is available",
            body.contains("catch (e: android.content.ActivityNotFoundException)"),
        )
        assertTrue(
            "any other failure (e.g. getUriForFile) must say what went wrong",
            body.contains("R.string.file_open_failed_toast"),
        )
    }
}
