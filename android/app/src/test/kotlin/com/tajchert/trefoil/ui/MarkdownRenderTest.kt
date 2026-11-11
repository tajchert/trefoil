package com.tajchert.trefoil.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownRenderTest {
    @get:Rule val compose = createComposeRule()

    private val sample = """
        ## Branches

        Here is **what changed** with `inline code`, ~~old~~ and a [link](https://example.com).

        | Branch | Contents |
        | --- | --- |
        | `custom` | Upstream `main` plus one commit: the custom build flavor, `fork/` scripts and `FORK.md` |
        | `t3/custom-t3-remote-adb` | `custom` plus one commit with the Android sharing feature |

        | Name | Count | Share |
        |:---|:---:|---:|
        | alpha | 12 | 40% |
        | beta | 3 |

        - Only 130 new districts
          - nested detail
        - [x] shipped
        - [ ] pending

        > Redirect table is required before deploy.

        ```kotlin
        val plan = Selector(dryRun = true)
        ```
    """.trimIndent()

    private fun render(dark: Boolean) {
        compose.setContent {
            TrefoilTheme(darkTheme = dark) {
                Box(Modifier.size(390.dp, 844.dp).background(Tf.colors.bg).padding(16.dp)) { MarkdownContent(sample, Modifier.fillMaxWidth()) }
            }
        }
        compose.waitForIdle()
        val output = File("build/reports/ui/design-markdown-${if (dark) "dark" else "light"}.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }

    @Test fun markdownDark() = render(true)
    @Test fun markdownLight() = render(false)
}
