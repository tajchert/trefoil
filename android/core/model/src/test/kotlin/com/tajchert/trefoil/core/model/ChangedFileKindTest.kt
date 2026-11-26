// Adapted from T3 Code (MIT), packages/shared/src/changedFileKind.test.ts @ 7deb51b550
package com.tajchert.trefoil.core.model

import com.tajchert.trefoil.core.model.ChangedFileKind.*
import org.junit.Assert.*
import org.junit.Test

class ChangedFileKindTest {
    private val cases = listOf(
        "src/contest.py" to Code,
        "test/fixtures/pr.json" to Mocks,
        "src/MockTransport.ts" to Mocks,
        "src/FakeClock.ts" to Mocks,
        "src/Foo.Mock.ts" to Mocks,
        "src/Mockup.ts" to Code,
        "src/Stubborn.ts" to Code,
        "pnpm-lock.yaml" to Generated,
        "convex/_generated/api.d.ts" to Generated,
        "src/agent.eval.ts" to Tests,
        "evals/title-generation.ts" to Tests,
        "src/Button.stories.tsx" to Tests,
        "src/Button.stories.ts" to Tests,
        "src/Button.stories.js" to Tests,
        "src/Button.stories.jsx" to Tests,
        "src/Button.stories.mjs" to Tests,
        "src/Button.stories.svelte" to Tests,
        "src/Button.stories.mdx" to Tests,
        "src\\Button.STORIES.TSX" to Tests,
        "src/Button.stories.generated.ts" to Generated,
        "__mocks__/Button.stories.tsx" to Mocks,
        "src/stories.ts" to Code,
        "src/stories/Button.tsx" to Code,
        ".storybook/main.ts" to Code,
        "README.md" to Docs,
        "tests/README.md" to Tests,
    )

    @Test fun `classifyChangedFile matches the upstream table`() {
        for ((path, kind) in cases) assertEquals(path, kind, classifyChangedFile(path))
    }

    @Test fun `android and kotlin conventions`() {
        assertEquals(Tests, classifyChangedFile("app/src/test/kotlin/com/x/FooTest.kt"))
        assertEquals(Tests, classifyChangedFile("app/src/androidTest/kotlin/com/x/FooTests.kt"))
        assertEquals(Code, classifyChangedFile("app/src/main/kotlin/com/x/Latest.kt"))
        assertEquals(Generated, classifyChangedFile("gradle/libs.versions.lock/gradle.lockfile"))
        assertEquals(Generated, classifyChangedFile("src/api.pb.go"))
        assertEquals(Docs, classifyChangedFile("docs/guide.txt"))
        assertEquals(Code, classifyChangedFile("requirements-dev.txt"))
    }

    @Test fun `isTestChangedFile counts tests and their support files`() {
        assertTrue(isTestChangedFile("src/app.test.ts"))
        assertTrue(isTestChangedFile("test/fixtures/pr.json"))
        assertTrue(isTestChangedFile("src/__snapshots__/a.snap"))
        assertFalse(isTestChangedFile("src/app.ts"))
        assertFalse(isTestChangedFile("pnpm-lock.yaml"))
    }

    @Test fun `isGeneratedChangedFile counts lockfiles and generated output`() {
        assertTrue(isGeneratedChangedFile("pnpm-lock.yaml"))
        assertTrue(isGeneratedChangedFile("public/app.min.js"))
        assertTrue(isGeneratedChangedFile("src/_generated/api.ts"))
        assertFalse(isGeneratedChangedFile("src/app.ts"))
        assertFalse(isGeneratedChangedFile("src/app.test.ts"))
    }
}
