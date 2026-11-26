// Adapted from T3 Code (MIT), packages/client-runtime/src/diffFileOrder.ts @ 7deb51b550
package com.tajchert.trefoil.core.model

/*
 * Review order from paths alone: source files, then tests (after the source they cover), then generated files, regrouped
 * so each directory stays contiguous.
 *
 * Dropped from upstream: the import graph. Upstream reads the added and removed lines of each source file to put
 * dependencies before dependents; that needs file contents and a per-language specifier resolver, and Trefoil orders
 * before it parses anything. Without imports the source tier falls back to path order, which is what upstream does for
 * files that import nothing in the diff. Everything else (tiers, test pairing, tree regrouping, kept duplicates) is
 * ported as is.
 */

private enum class DiffFileTier { Source, Test, Generated }

private val GENERATED_FILE_NAMES = setOf(
    "pnpm-lock.yaml", "yarn.lock", "package-lock.json", "bun.lockb", "Cargo.lock", "go.sum", "composer.lock", "Gemfile.lock",
)
private val GENERATED_DIRECTORIES = setOf("__snapshots__", "__generated__", "dist", "build", "vendor")
private val TEST_DIRECTORIES = setOf("__tests__", "tests", "test")
private val GENERATED_INFIX = Regex("""\.generated\.""")
private val TEST_INFIX = Regex("""\.(?:test|spec)\.""")
private val TEST_SUFFIX = Regex("""\.(?:test|spec)\..*$""")

private fun diffFileTier(path: String): DiffFileTier {
    val segments = path.split('/')
    val name = segments.last()
    val directories = segments.subList(0, segments.size - 1)
    if (name in GENERATED_FILE_NAMES || name.endsWith(".snap") || name.endsWith(".min.js") || name.endsWith(".min.css") ||
        GENERATED_INFIX.containsMatchIn(name) || directories.any { it in GENERATED_DIRECTORIES }
    ) {
        return DiffFileTier.Generated
    }
    if (TEST_INFIX.containsMatchIn(name) || directories.any { it in TEST_DIRECTORIES }) return DiffFileTier.Test
    return DiffFileTier.Source
}

private fun stripExtension(path: String): String {
    val dot = path.lastIndexOf('.')
    val slash = path.lastIndexOf('/')
    return if (dot > slash) path.substring(0, dot) else path
}

private fun baseName(path: String): String = stripExtension(path.substringAfterLast('/'))

// ICU orders punctuation before digits before letters, and compares letters case-insensitively first.
private const val ICU_PUNCTUATION = " _-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$"

private fun collationRank(c: Char): Int = when {
    c.isLetter() -> 2000 + c.lowercaseChar().code
    c.isDigit() -> 1000 + c.code
    else -> ICU_PUNCTUATION.indexOf(c).let { if (it >= 0) it else ICU_PUNCTUATION.length + c.code }
}

/** Approximation of JS `localeCompare` for paths: punctuation < digits < letters, case-insensitive, lowercase first on ties. */
private val pathCollator = Comparator<String> { left, right ->
    val common = minOf(left.length, right.length)
    for (index in 0 until common) {
        val difference = collationRank(left[index]) - collationRank(right[index])
        if (difference != 0) return@Comparator difference
    }
    if (left.length != right.length) return@Comparator left.length - right.length
    for (index in left.indices) {
        val l = left[index]
        val r = right[index]
        if (l != r) return@Comparator if (l.isLowerCase()) -1 else 1
    }
    0
}

// `a.logic.test.ts` covers `a.logic.ts` while `a.browser.test.ts` covers `a.ts`, so the longest dotted prefix that names a
// source file wins.
private fun testedSourcePosition(path: String, sourcePositions: Map<String, Int>): Int {
    var name = path.substringAfterLast('/').replace(TEST_SUFFIX, "")
    while (true) {
        sourcePositions[name]?.let { return it }
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return Int.MAX_VALUE
        name = name.substring(0, dot)
    }
}

private fun orderByTier(paths: Collection<String>): List<String> {
    val tiers = paths.associateWith { diffFileTier(it) }
    val orderedSource = paths.filter { tiers[it] == DiffFileTier.Source }.sortedWith(pathCollator)
    val sourcePositions = HashMap<String, Int>()
    orderedSource.forEachIndexed { index, path -> sourcePositions.putIfAbsent(baseName(path), index) }
    val orderedTests = paths.filter { tiers[it] == DiffFileTier.Test }.sortedWith(
        compareBy<String> { testedSourcePosition(it, sourcePositions) }.thenComparator { a, b -> pathCollator.compare(a, b) },
    )
    val orderedGenerated = paths.filter { tiers[it] == DiffFileTier.Generated }.sortedWith(pathCollator)
    return orderedSource + orderedTests + orderedGenerated
}

/** Where each file, and each directory (with a trailing slash), first appears in [paths]. */
fun diffFileTreePositions(paths: List<String>): Map<String, Int> {
    val positions = LinkedHashMap<String, Int>()
    paths.forEachIndexed { index, path ->
        positions[path] = index
        var directory = ""
        val segments = path.split('/')
        for (segment in segments.subList(0, segments.size - 1)) {
            directory += "$segment/"
            positions.putIfAbsent(directory, index)
        }
    }
    return positions
}

private fun orderLikeTree(paths: List<String>): List<String> {
    val positions = diffFileTreePositions(paths)
    val keyed = paths.map { path ->
        val key = ArrayList<Int>()
        var directory = ""
        val segments = path.split('/')
        for (segment in segments.subList(0, segments.size - 1)) {
            directory += "$segment/"
            key.add(positions.getValue(directory))
        }
        key.add(positions.getValue(path))
        path to key
    }
    return keyed.sortedWith { (_, left), (_, right) ->
        val length = minOf(left.size, right.size)
        for (index in 0 until length) {
            val difference = left[index] - right[index]
            if (difference != 0) return@sortedWith difference
        }
        left.size - right.size
    }.map { it.first }
}

/**
 * Review order shared by every client: tests after their source, generated files last, then regrouped so each directory
 * stays contiguous and a file tree built from [diffFileTreePositions] lists files in the same order. A path listed twice
 * (a type change is a delete and an add under one path) keeps both entries, adjacent.
 */
fun orderDiffFilesForReview(paths: List<String>): List<String> {
    val counts = LinkedHashMap<String, Int>()
    for (path in paths) counts[path] = (counts[path] ?: 0) + 1
    val ordered = orderLikeTree(orderByTier(counts.keys))
    return ordered.flatMap { path -> List(counts.getValue(path)) { path } }
}
