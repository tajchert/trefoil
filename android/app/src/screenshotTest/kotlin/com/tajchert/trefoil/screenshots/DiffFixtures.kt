package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.DiffChange
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DiffFile
import com.tajchert.trefoil.core.model.DiffLineKind
import com.tajchert.trefoil.core.model.DiffRow

/** Hand-built, synthetic diff documents for the diff viewer screenshots (no parser, no real files). */
internal object DiffFixtures {
    /** Builds a [DiffDocument] row by row; line numbers follow the hunk header automatically. */
    class Builder {
        val rows = mutableListOf<DiffRow>()
        val files = mutableListOf<DiffFile>()
        private var nextId = 0L

        fun file(
            path: String, change: DiffChange = DiffChange.Modified, oldPath: String? = null,
            collapsedByDefault: Boolean = false, repository: String? = null, body: FileScope.() -> Unit = {},
        ) {
            val index = files.size
            val first = rows.size
            rows += DiffRow.FileHeader(nextId++, index)
            val scope = FileScope(index)
            scope.body()
            files += DiffFile(
                index = index, path = path, oldPath = oldPath, change = change, additions = scope.additions, deletions = scope.deletions,
                collapsedByDefault = collapsedByDefault, firstRowIndex = first, endRowIndex = rows.size, lineCount = scope.lines,
                repository = repository,
            )
        }

        inner class FileScope(private val fileIndex: Int) {
            var additions = 0
            var deletions = 0
            var lines = 0
            private var oldNumber = 1
            private var newNumber = 1

            fun hunk(oldStart: Int, newStart: Int, context: String = "") {
                oldNumber = oldStart
                newNumber = newStart
                rows += DiffRow.Hunk(nextId++, fileIndex, "@@ -$oldStart +$newStart @@${if (context.isEmpty()) "" else " $context"}")
            }

            fun ctx(text: String) = line(DiffLineKind.Context, text, null)
            fun add(text: String, words: IntArray? = null) = line(DiffLineKind.Add, text, words)
            fun del(text: String, words: IntArray? = null) = line(DiffLineKind.Del, text, words)
            fun notice(text: String) { rows += DiffRow.Notice(nextId++, fileIndex, text) }

            private fun line(kind: DiffLineKind, text: String, words: IntArray?) {
                val old = if (kind != DiffLineKind.Add) oldNumber++ else null
                val new = if (kind != DiffLineKind.Del) newNumber++ else null
                if (kind == DiffLineKind.Add) additions++
                if (kind == DiffLineKind.Del) deletions++
                lines++
                rows += DiffRow.Line(nextId++, fileIndex, kind, old, new, text, words)
            }
        }

        fun build(truncated: Boolean = false, rowCapHit: Boolean = false) = DiffDocument(files.toList(), rows.toList(), truncated, rowCapHit)
    }

    private inline fun doc(truncated: Boolean = false, rowCapHit: Boolean = false, block: Builder.() -> Unit): DiffDocument =
        Builder().apply(block).build(truncated, rowCapHit)

    private fun Builder.FileScope.greeterBody() {
        hunk(12, 12, "class Greeter {")
        ctx("    private val greeting: String,")
        ctx("    private val punctuation: String = \"!\",")
        ctx(") {")
        del("    fun greet(name: String): String {", intArrayOf(8, 12))
        add("    fun greet(name: String, formal: Boolean = false): String {", intArrayOf(8, 12, 30, 44))
        del("        return greeting + \", \" + name + punctuation")
        add("        val prefix = if (formal) \"Dear \" else \"\"")
        add("        return \"\$greeting, \$prefix\$name\$punctuation\"")
        ctx("    }")
        ctx("}")
        hunk(41, 42, "fun main() {")
        ctx("    val greeter = Greeter(\"Hello\")")
        del("    println(greeter.greet(\"world\"))", intArrayOf(12, 26))
        add("    println(greeter.greet(\"world\", formal = true))", intArrayOf(12, 26, 28, 47))
        ctx("}")
    }

    /** Two modified files, with word-diff spans. */
    val small: DiffDocument = doc {
        file("app/src/main/kotlin/demo/Greeter.kt") { greeterBody() }
        file("docs/USAGE.md") {
            hunk(3, 3)
            ctx("Run the greeter from the command line:")
            ctx("")
            del("    greeter --name world", intArrayOf(12, 21))
            add("    greeter --name world --formal", intArrayOf(12, 21, 21, 29))
            ctx("")
        }
    }

    /** A generated and a long file start collapsed; a small one is open. */
    val largeCollapsed: DiffDocument = doc {
        file("gen/api/Client.generated.kt", collapsedByDefault = true) {
            hunk(1, 1)
            repeat(180) { n -> add("    fun endpoint$n(request: Request$n): Response$n = call(\"/v1/endpoint/$n\", request)") }
            repeat(60) { n -> del("    fun legacy$n(): Unit = TODO()") }
        }
        file("app/src/main/kotlin/demo/Greeter.kt") { greeterBody() }
        file("app/src/main/kotlin/demo/Catalog.kt", collapsedByDefault = true) {
            hunk(1, 1, "object Catalog {")
            repeat(450) { n -> add("    val item$n = Item(id = $n, name = \"Item $n\")") }
        }
        file("scripts/one-line.sh", change = DiffChange.Added) {
            hunk(0, 1)
            add("#!/bin/sh")
        }
    }

    /** Rename with edits, a binary change, an added file and a deleted file. */
    val renameBinary: DiffDocument = doc {
        file("app/src/main/kotlin/demo/Welcome.kt", change = DiffChange.Renamed, oldPath = "app/src/main/kotlin/demo/Greeting.kt") {
            hunk(1, 1)
            ctx("package demo")
            ctx("")
            del("class Greeting", intArrayOf(6, 14))
            add("class Welcome", intArrayOf(6, 13))
        }
        file("app/src/main/res/drawable/logo.png", change = DiffChange.Binary) {
            notice("Binary file not shown")
        }
        file("app/src/main/kotlin/demo/Farewell.kt", change = DiffChange.Added) {
            hunk(0, 1)
            add("package demo")
            add("")
            add("class Farewell")
        }
        file("app/src/main/kotlin/demo/Legacy.kt", change = DiffChange.Deleted) {
            hunk(1, 0)
            del("package demo")
            del("")
            del("class Legacy")
        }
    }

    /** A small diff the server cut off, and one the row cap cut. */
    val truncated: DiffDocument = doc(truncated = true, rowCapHit = true) {
        file("app/src/main/kotlin/demo/Greeter.kt") { greeterBody() }
    }

    /** Long lines to compare no-wrap (clipped, scrolls sideways) and wrap. */
    val longLines: DiffDocument = doc {
        file("app/src/main/kotlin/demo/Config.kt") {
            hunk(20, 20, "object Config {")
            ctx("    val endpoints = listOf(\"https://alpha.example.test/api/v1/items\", \"https://beta.example.test/api/v1/items\", \"https://gamma.example.test/api/v1/items\")")
            del("    const val timeoutMillis = 5_000L", intArrayOf(24, 36))
            add("    const val timeoutMillis = 30_000L // generous: the staging environment answers slowly after a cold start", intArrayOf(24, 37, 39, 103))
            ctx("    val retryPolicy = RetryPolicy(maxAttempts = 3, backoffMillis = 250, jitter = true, retryOn = listOf(502, 503, 504))")
        }
    }
}
