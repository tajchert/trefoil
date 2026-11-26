// Adapted from T3 Code (MIT), packages/shared/src/changedFileKind.ts @ 7deb51b550
package com.tajchert.trefoil.core.model

/** How a changed file is sorted for review, decided from its path alone. */
enum class ChangedFileKind { Code, Tests, Mocks, Generated, Docs }

private val GENERATED_FILES = setOf(
    "bun.lock", "bun.lockb", "cargo.lock", "composer.lock", "deno.lock", "flake.lock", "gemfile.lock", "go.sum",
    "gradle.lockfile", "mix.lock", "npm-shrinkwrap.json", "package-lock.json", "packages.lock.json", "pipfile.lock",
    "pnpm-lock.yaml", "podfile.lock", "poetry.lock", "pubspec.lock", "uv.lock", "yarn.lock",
)
private val GENERATED_NAME =
    Regex("""(\.generated\.|\.gen\.|\.g\.dart$|\.pb\.go$|_pb2(_grpc)?\.py$|\.pb\.(cc|h)$|\.min\.(js|css)$|\.(js|css)\.map$)""")
private val GENERATED_DIRS = setOf("__generated__", "_generated", "generated")

private val MOCK_NAME = Regex("""(\.snap$|\.(mocks?|fixtures?|stubs?|fakes?)\.[^.]+$)""", RegexOption.IGNORE_CASE)

// Case-sensitive after the prefix: `MockClock.ts` is a mock, `Mockup.ts` is not.
private val MOCK_PREFIX = Regex("""^([Mm]ocks?|[Ff]ixtures?|[Ss]tubs?|[Ff]akes?)([-_.A-Z]|$)""")
private val MOCK_DIRS = setOf(
    "__fixtures__", "__mocks__", "__snapshots__", "fakes", "fixture", "fixtures", "mock", "mocks", "stubs", "testdata",
)

private val TEST_NAME = Regex(
    """(\.(test|spec|e2e|eval|stories)\.[^.]+$|_(test|spec)\.[^.]+$|^test_.+\.py$|^conftest\.py$|^(vitest|jest)\.setup\.[^.]+$)""",
    RegexOption.IGNORE_CASE,
)

// Case-sensitive: `FooTest.java` is a test class, `Latest.java` is not.
private val CLASS_TEST_NAME = Regex("""(Tests?|Spec)\.(java|kt|kts|scala|groovy|cs|php|swift|m)$""")
private val TEST_DIRS = setOf("__tests__", "__test__", "cypress", "e2e", "evals", "spec", "specs", "test", "tests")

private val DOCS_NAME = Regex("""\.(md|mdx|markdown|rst|adoc|txt)$""", RegexOption.IGNORE_CASE)

// Plain-text files that tools read as config or input, not prose.
private val NOT_DOCS_NAME = Regex("""^(requirements.*|constraints.*|CMakeLists|robots|llms)\.txt$""", RegexOption.IGNORE_CASE)

/**
 * Sorts a changed file by its path alone, using common naming conventions. Lockfiles and build output count as
 * generated, test support (mocks, fixtures, snapshots) is kept apart from the tests themselves, and prose is its own
 * kind, so a reviewer can see how much of a change is logic.
 */
fun classifyChangedFile(path: String): ChangedFileKind {
    val segments = path.replace('\\', '/').split('/')
    val name = segments.last()
    val dirs = segments.subList(0, segments.size - 1).map { it.lowercase() }
    fun inDir(set: Set<String>) = dirs.any { it in set }
    return when {
        name.lowercase() in GENERATED_FILES || GENERATED_NAME.containsMatchIn(name) || inDir(GENERATED_DIRS) ->
            ChangedFileKind.Generated
        MOCK_NAME.containsMatchIn(name) || MOCK_PREFIX.containsMatchIn(name) || inDir(MOCK_DIRS) -> ChangedFileKind.Mocks
        TEST_NAME.containsMatchIn(name) || CLASS_TEST_NAME.containsMatchIn(name) || inDir(TEST_DIRS) -> ChangedFileKind.Tests
        DOCS_NAME.containsMatchIn(name) && !NOT_DOCS_NAME.matches(name) -> ChangedFileKind.Docs
        else -> ChangedFileKind.Code
    }
}

/** Tests and their support files (mocks, fixtures, snapshots). */
fun isTestChangedFile(path: String): Boolean = classifyChangedFile(path).let { it == ChangedFileKind.Tests || it == ChangedFileKind.Mocks }

/** Lockfiles, build output and other generated files. */
fun isGeneratedChangedFile(path: String): Boolean = classifyChangedFile(path) == ChangedFileKind.Generated
