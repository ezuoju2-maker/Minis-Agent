package com.openminis.app.tools

import java.io.File

/**
 * [v1.62-repo-map] Token-budgeted repository outline ("repo map").
 *
 * The agent loop pays for every file it reads. On a large project the model
 * either burns its context on whole files or wanders blindly guessing paths.
 * Aider solved this with a graph-ranked skeleton of the repo; this is the same
 * idea implemented without a native parser (the app ships no tree-sitter):
 *
 *   1. Walk the tree once, skipping build output, VCS metadata and vendored
 *      dependencies.
 *   2. Extract *declarations* per file with language-specific regexes
 *      (class / object / interface / fun / def / func / struct / impl …).
 *   3. Rank files by a cheap approximation of the dependency graph: a file
 *      that others import (basename or package tail appears in their import
 *      lines) and that declares many symbols rises; deep paths fall.
 *   4. Render the highest-ranked files first under a hard character budget,
 *      with line numbers, so the model can jump straight to `path:line`.
 *
 * Everything is pure (only reads [root]) and deterministic: same tree in,
 * same map out. It is a *navigation aid*, not a substitute for reading the
 * file the model actually needs — the returned text says so.
 */
object RepoMapBuilder {

    /** Directories never worth indexing (build output, metadata, vendoring). */
    private val SKIP_DIRS = setOf(
        ".git", ".hg", ".svn", ".idea", ".gradle", ".kotlin", ".cxx", ".venv", "venv",
        "node_modules", "build", "dist", "out", "target", "coverage", "__pycache__",
        "Pods", "DerivedData", ".dart_tool", ".pub-cache", "vendor", ".next", ".cache",
        "tmp", "temp", ".mypy_cache", ".pytest_cache",
    )

    /** Extensions the map understands; everything else is ignored. */
    private val LANGUAGE_BY_EXT = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "java" to "java",
        "py" to "python", "pyi" to "python",
        "js" to "js", "jsx" to "js", "mjs" to "js", "cjs" to "js",
        "ts" to "ts", "tsx" to "ts",
        "go" to "go",
        "rs" to "rust",
        "swift" to "swift",
        "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "hpp" to "cpp",
        "cs" to "csharp",
        "rb" to "ruby",
        "php" to "php",
        "sh" to "shell", "bash" to "shell",
    )

    private const val MAX_FILE_BYTES = 512_000L
    private const val MAX_SYMBOLS_PER_FILE = 24
    private const val MAX_FILES_DEFAULT = 1_500

    data class Options(
        /** Hard cap on returned characters. */
        val maxChars: Int = 18_000,
        /** Hard cap on files inspected. */
        val maxFiles: Int = MAX_FILES_DEFAULT,
        /** Optional keywords: matching paths/declarations rank first. */
        val focus: List<String> = emptyList(),
        /** Subdirectory prefixes to ignore (already-resolved, relative). */
        val exclude: List<String> = emptyList(),
    )

    data class Symbol(val kind: String, val name: String, val line: Int)

    internal data class SourceFile(
        val relPath: String,
        val language: String,
        val sizeBytes: Long,
        val symbols: List<Symbol>,
        val imports: List<String>,
        val lineCount: Int,
    )

    /** Build the map for [root]; returns a short honest note when empty. */
    fun build(root: File, options: Options = Options()): String {
        if (!root.exists() || !root.isDirectory) {
            return "repo_map: not a directory: ${root.path}"
        }
        val files = scan(root, options)
        if (files.isEmpty()) {
            return "repo_map: no supported source files under ${root.path}"
        }
        val ranked = rank(files, options.focus)
        return render(root, ranked, files.size, options)
    }

    /** Walk [root] and collect declarations. Pure apart from file reads. */
    internal fun scan(root: File, options: Options): List<SourceFile> {
        val out = ArrayList<SourceFile>()
        val excludes = options.exclude.map { it.trim('/') }.filter { it.isNotEmpty() }
        fun excluded(rel: String): Boolean = excludes.any { rel == it || rel.startsWith("$it/") }

        fun walk(dir: File, depth: Int) {
            if (out.size >= options.maxFiles || depth > 12) return
            val children = dir.listFiles() ?: return
            // Deterministic order: directories and files sorted by name.
            val sorted = children.sortedBy { it.name }
            for (child in sorted) {
                if (out.size >= options.maxFiles) return
                val rel = child.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
                if (excluded(rel)) continue
                if (child.isDirectory) {
                    if (child.name.startsWith(".") && child.name != ".") continue
                    if (child.name in SKIP_DIRS) continue
                    walk(child, depth + 1)
                } else {
                    val lang = LANGUAGE_BY_EXT[child.extension.lowercase()] ?: continue
                    if (child.length() > MAX_FILE_BYTES) continue
                    val parsed = parseFile(child, rel, lang) ?: continue
                    if (parsed.symbols.isEmpty() && parsed.imports.isEmpty()) continue
                    out.add(parsed)
                }
            }
        }
        walk(root, 0)
        return out
    }

    private fun parseFile(file: File, rel: String, language: String): SourceFile? {
        return try {
            val lines = file.bufferedReader().use { it.readLines() }
            val symbols = ArrayList<Symbol>()
            val imports = ArrayList<String>()
            val seen = HashSet<String>()
            lines.forEachIndexed { idx, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachIndexed
                if (imports.size < 40 && IMPORT_PATTERN.containsMatchIn(line)) {
                    imports.add(line)
                }
                if (symbols.size >= MAX_SYMBOLS_PER_FILE) return@forEachIndexed
                val sym = declarationOf(line, language) ?: return@forEachIndexed
                val key = "${sym.kind}:${sym.name}"
                if (seen.add(key)) symbols.add(sym.copy(line = idx + 1))
            }
            SourceFile(rel, language, file.length(), symbols, imports, lines.size)
        } catch (_: Exception) {
            null
        }
    }

    private val IMPORT_PATTERN = Regex("""^(import|from|#include|using|require|use)\b""")

    /**
     * Declarations this map reports. Deliberately only *type and function*
     * level: fields and local variables would drown the useful signal. The
     * regexes are anchored at line start (after trim) so commented-out code
     * indented inside an example is unlikely to match.
     */
    internal fun declarationOf(line: String, language: String): Symbol? = when (language) {
        "kotlin" -> kotlinDeclaration(line)
        "java", "csharp" -> javaLikeDeclaration(line)
        "python" -> pythonDeclaration(line)
        "js", "ts" -> jsTsDeclaration(line)
        "go" -> goDeclaration(line)
        "rust" -> rustDeclaration(line)
        "swift" -> swiftDeclaration(line)
        "c", "cpp" -> cDeclaration(line)
        "ruby" -> rubyDeclaration(line)
        "php" -> phpDeclaration(line)
        "shell" -> shellDeclaration(line)
        else -> null
    }

    private fun clean(name: String): String = name.trim().trimEnd('{', '(', ':', '=', ' ', '\t', '<')

    private fun kotlinDeclaration(line: String): Symbol? {
        // modifiers first (public/internal/data/sealed/suspend/inline/override…)
        val mods = """((?:public|private|protected|internal|open|abstract|final|data|sealed|enum|annotation|value|inline|suspend|operator|infix|tailrec|external|override|const|lateinit|actual|expect)\s+)*"""
        Regex("^$mods(class|interface|object|typealias)\\s+([A-Za-z_][A-Za-z0-9_]*)")
            .find(line)?.let { m ->
                return Symbol(m.groupValues[2], clean(m.groupValues[3]), 0)
            }
        Regex("^$mods(enum\\s+class|companion\\s+object)\\s*([A-Za-z_][A-Za-z0-9_]*)?")
            .find(line)?.let { m ->
                val name = clean(m.groupValues[3]).ifEmpty { "Companion" }
                return Symbol("object", name, 0)
            }
        Regex("^$mods(fun)\\s+(?:<[^>]+>\\s*)?([A-Za-z_][A-Za-z0-9_]*)")
            .find(line)?.let { m ->
                return Symbol("fun", clean(m.groupValues[3]), 0)
            }
        // extension function: fun Receiver.name(
        Regex("^$mods(fun)\\s+[A-Za-z0-9_.<>?]+\\.([A-Za-z_][A-Za-z0-9_]*)")
            .find(line)?.let { m ->
                return Symbol("fun", clean(m.groupValues[3]), 0)
            }
        return null
    }

    private fun javaLikeDeclaration(line: String): Symbol? {
        Regex("""^(?:(?:public|private|protected|abstract|final|static|sealed|non-sealed|strictfp)\s+)*(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .find(line)?.let { m -> return Symbol(m.groupValues[1], clean(m.groupValues[2]), 0) }
        // Java methods: `public static void main(` — take the identifier before `(`.
        Regex("""^(?:(?:public|private|protected|static|final|abstract|synchronized|native|default)\s+)+[\w<>\[\],.?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
            .find(line)?.let { m -> return Symbol("fun", clean(m.groupValues[1]), 0) }
        return null
    }

    private fun pythonDeclaration(line: String): Symbol? {
        Regex("""^(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)""").find(line)?.let { m ->
            return Symbol("fun", clean(m.groupValues[1]), 0)
        }
        Regex("""^class\s+([A-Za-z_][A-Za-z0-9_]*)""").find(line)?.let { m ->
            return Symbol("class", clean(m.groupValues[1]), 0)
        }
        return null
    }

    private fun jsTsDeclaration(line: String): Symbol? {
        Regex("""^(?:export\s+)?(?:default\s+)?(?:abstract\s+)?(class|interface|enum|type)\s+([A-Za-z_$][\w$]*)""")
            .find(line)?.let { m -> return Symbol(m.groupValues[1], clean(m.groupValues[2]), 0) }
        Regex("""^(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)""")
            .find(line)?.let { m -> return Symbol("fun", clean(m.groupValues[1]), 0) }
        Regex("""^(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>""")
            .find(line)?.let { m -> return Symbol("fun", clean(m.groupValues[1]), 0) }
        return null
    }

    private fun goDeclaration(line: String): Symbol? {
        Regex("""^func\s+(?:\([^)]*\)\s*)?([A-Za-z_][A-Za-z0-9_]*)""").find(line)?.let { m ->
            return Symbol("fun", clean(m.groupValues[1]), 0)
        }
        Regex("""^type\s+([A-Za-z_][A-Za-z0-9_]*)""").find(line)?.let { m ->
            return Symbol("type", clean(m.groupValues[1]), 0)
        }
        return null
    }

    private fun rustDeclaration(line: String): Symbol? {
        Regex("""^(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?(fn|struct|enum|trait|mod|type)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .find(line)?.let { m -> return Symbol(m.groupValues[1], clean(m.groupValues[2]), 0) }
        Regex("""^impl(?:<[^>]*>)?\s+([A-Za-z_][A-Za-z0-9_:]*)""").find(line)?.let { m ->
            return Symbol("impl", clean(m.groupValues[1]), 0)
        }
        return null
    }

    private fun swiftDeclaration(line: String): Symbol? {
        Regex("""^(?:@\w+\s+)*(?:(?:public|private|internal|fileprivate|open|final|static)\s+)*(class|struct|enum|protocol|extension)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .find(line)?.let { m -> return Symbol(m.groupValues[1], clean(m.groupValues[2]), 0) }
        Regex("""^(?:@\w+\s+)*(?:(?:public|private|internal|fileprivate|open|final|static|override)\s+)*func\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .find(line)?.let { m -> return Symbol("fun", clean(m.groupValues[1]), 0) }
        return null
    }

    private fun cDeclaration(line: String): Symbol? {
        Regex("""^(?:typedef\s+)?(?:class|struct|union|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .find(line)?.let { m -> return Symbol("type", clean(m.groupValues[1]), 0) }
        // A function definition: return type + name + `(`, line ends with `{` or `)`.
        Regex("""^[A-Za-z_][\w:<>*&\s]*?\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*\{?\s*$""")
            .find(line)?.let { m ->
                val name = m.groupValues[1]
                if (name in setOf("if", "for", "while", "switch", "return")) return null
                return Symbol("fun", name, 0)
            }
        return null
    }

    private fun rubyDeclaration(line: String): Symbol? {
        Regex("""^(class|module)\s+([A-Z][A-Za-z0-9_:]*)""").find(line)?.let { m ->
            return Symbol(m.groupValues[1], clean(m.groupValues[2]), 0)
        }
        Regex("""^def\s+(?:self\.)?([A-Za-z_][A-Za-z0-9_?!]*)""").find(line)?.let { m ->
            return Symbol("fun", clean(m.groupValues[1]), 0)
        }
        return null
    }

    private fun phpDeclaration(line: String): Symbol? {
        Regex("""^(?:(?:abstract|final)\s+)?(class|interface|trait)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .find(line)?.let { m -> return Symbol(m.groupValues[1], clean(m.groupValues[2]), 0) }
        Regex("""^(?:(?:public|private|protected|static|final)\s+)*function\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .find(line)?.let { m -> return Symbol("fun", clean(m.groupValues[1]), 0) }
        return null
    }

    private fun shellDeclaration(line: String): Symbol? {
        Regex("""^(?:function\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{""").find(line)?.let { m ->
            return Symbol("fun", clean(m.groupValues[1]), 0)
        }
        return null
    }

    // ---- ranking -----------------------------------------------------------

    internal fun rank(files: List<SourceFile>, focus: List<String>): List<SourceFile> {
        val focusLower = focus.map { it.lowercase() }.filter { it.isNotBlank() }
        // A file is "imported" when another file's import line mentions its
        // basename (without extension) or its dotted path tail.
        val byStem = HashMap<String, MutableList<SourceFile>>()
        for (f in files) {
            byStem.getOrPut(stem(f.relPath)) { ArrayList() }.add(f)
        }
        val inDegree = HashMap<String, Int>()
        // Inverted index instead of an O(files × imports × files) scan: an
        // import line is tokenised and each identifier is looked up among
        // file stems. Bounded by imports × tokens, which is what makes a
        // 1500-file project map in well under a second on a phone.
        val tokenRe = Regex("[A-Za-z_][A-Za-z0-9_]*")
        for (f in files) {
            for (imp in f.imports) {
                for (tok in tokenRe.findAll(imp).map { it.value }) {
                    val candidates = byStem[tok] ?: continue
                    for (candidate in candidates) {
                        if (candidate === f) continue
                        inDegree[candidate.relPath] = (inDegree[candidate.relPath] ?: 0) + 1
                    }
                }
            }
        }
        val maxIn = (inDegree.values.maxOrNull() ?: 1).coerceAtLeast(1)
        val maxSymbols = (files.maxOfOrNull { it.symbols.size } ?: 1).coerceAtLeast(1)
        val maxDepth = (files.maxOfOrNull { it.relPath.count { c -> c == '/' } } ?: 1).coerceAtLeast(1)

        fun score(f: SourceFile): Double {
            val symbolScore = f.symbols.size.toDouble() / maxSymbols
            val importScore = (inDegree[f.relPath] ?: 0).toDouble() / maxIn
            val depth = f.relPath.count { it == '/' }.toDouble() / maxDepth
            var s = 2.2 * symbolScore + 1.6 * importScore + 0.8 * (1.0 - depth)
            if (focusLower.isNotEmpty()) {
                val path = f.relPath.lowercase()
                val hits = focusLower.count { path.contains(it) } +
                    focusLower.count { k -> f.symbols.any { it.name.lowercase().contains(k) } }
                s += 1.2 * hits
            }
            // Very small files are usually generated stubs or config.
            if (f.lineCount < 8) s -= 0.4
            return s
        }
        return files.sortedWith(compareByDescending<SourceFile> { score(it) }.thenBy { it.relPath })
    }

    private fun stem(relPath: String): String {
        val base = relPath.substringAfterLast('/')
        return base.substringBeforeLast('.')
    }

    // ---- rendering ---------------------------------------------------------

    internal fun render(root: File, ranked: List<SourceFile>, totalFiles: Int, options: Options): String {
        val sb = StringBuilder()
        sb.append("Repository map: ").append(root.name).append(" — ")
            .append(totalFiles).append(" source files indexed, ranked by structural importance.\n")
        sb.append("Symbols are declarations only (kind name:line). Read the file before editing; ")
            .append("this map is for navigation.\n\n")
        var emitted = 0
        for (f in ranked) {
            if (sb.length >= options.maxChars) break
            val header = "${f.relPath} (${f.lineCount} lines)\n"
            if (sb.length + header.length > options.maxChars) break
            sb.append(header)
            emitted++
            for (sym in f.symbols) {
                val row = "  ${sym.kind} ${sym.name}:${sym.line}\n"
                if (sb.length + row.length > options.maxChars) break
                sb.append(row)
            }
            sb.append('\n')
        }
        if (emitted < ranked.size) {
            sb.append("… ${ranked.size - emitted} more files omitted (budget ${options.maxChars} chars). ")
            sb.append("Narrow with `path` or `focus`, or raise `max_tokens`.\n")
        }
        return sb.toString().trimEnd()
    }
}
