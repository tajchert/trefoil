// Adapted from T3 Code (MIT), packages/client-runtime/src/diffFileOrder.test.ts @ 7deb51b550
package com.tajchert.trefoil.core.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Upstream cases that need file contents (import specifiers, the random topological-order comparison) are not ported:
 * the Kotlin port orders by paths only.
 */
class DiffFileOrderTest {
    @Test fun `places source before tests and generated files across path conventions`() {
        val source = listOf("src/app.ts", "src/dist.ts", "src/testing.ts")
        val tests = listOf("src/__tests__/app.ts", "src/app.spec.tsx", "src/app.test.ts", "test/app.ts", "tests/helpers/app.ts")
        val generated = listOf(
            "apps/web/package-lock.json", "dist/app.js", "packages/core/vendor/lib.js", "pnpm-lock.yaml", "public/app.min.js",
            "src/__snapshots__/app.ts", "src/api.generated.ts", "src/app.test.ts.snap",
        )
        assertEquals(
            source + tests.take(3) +
                // Generated files under src/ stay in src/ so the list matches the tree.
                listOf("src/__snapshots__/app.ts", "src/api.generated.ts", "src/app.test.ts.snap") +
                tests.drop(3) + generated.take(5),
            orderDiffFilesForReview((generated + tests + source).reversed()),
        )
    }

    @Test fun `keeps both entries of a path that changed type`() {
        assertEquals(
            listOf("AGENTS.md", "AGENTS.md", "CLAUDE.md"),
            orderDiffFilesForReview(listOf("CLAUDE.md", "AGENTS.md", "AGENTS.md")),
        )
    }

    @Test fun `pulls a test listed last into its directory keeping the first-seen order`() {
        assertEquals(
            listOf("server/device/Host.ts", "server/device/Host.test.ts", "server/ws.ts", "web/settings.ts", "web/settings.test.ts"),
            orderDiffFilesForReview(
                listOf("server/device/Host.ts", "web/settings.ts", "server/ws.ts", "server/device/Host.test.ts", "web/settings.test.ts"),
            ),
        )
    }

    @Test fun `keeps the tiers apart and orders tests by the source they cover`() {
        // Upstream lists b.ts before a.ts (a imports b), so b's tests come first there; by path alone a comes first.
        assertEquals(
            listOf("src/a.ts", "src/b.ts", "src/a.test.ts", "src/__tests__/b.ts", "dist/a.js", "pnpm-lock.yaml"),
            orderDiffFilesForReview(listOf("pnpm-lock.yaml", "src/a.test.ts", "src/__tests__/b.ts", "src/a.ts", "src/b.ts", "dist/a.js")),
        )
    }

    @Test fun `puts a test with no source in the diff after the ones that have theirs`() {
        assertEquals(
            listOf("src/a.ts", "src/a.test.ts", "src/orphan.spec.ts"),
            orderDiffFilesForReview(listOf("src/orphan.spec.ts", "src/a.test.ts", "src/a.ts")),
        )
    }

    @Test fun `pairs a test with the source whose name carries its own dotted suffix`() {
        assertEquals(
            listOf("src/a.logic.ts", "src/b.ts", "src/c.tsx", "src/a.logic.test.ts", "src/b.test.ts", "src/c.browser.test.tsx"),
            orderDiffFilesForReview(
                listOf("src/c.browser.test.tsx", "src/b.test.ts", "src/a.logic.test.ts", "src/c.tsx", "src/b.ts", "src/a.logic.ts"),
            ),
        )
    }

    @Test fun `tree positions record files and directories at first appearance`() {
        val positions = diffFileTreePositions(listOf("a/b/c.kt", "a/d.kt", "e.kt"))
        assertEquals(0, positions["a/"])
        assertEquals(0, positions["a/b/"])
        assertEquals(1, positions["a/d.kt"])
        assertEquals(2, positions["e.kt"])
    }

    @Test fun `empty list and single path`() {
        assertEquals(emptyList<String>(), orderDiffFilesForReview(emptyList()))
        assertEquals(listOf("x"), orderDiffFilesForReview(listOf("x")))
    }
}
