package com.tajchert.trefoil.core.model

import org.junit.Assert.*
import org.junit.Test

class RemoteFilesTest {
    @Test fun findsAuthoredAbsoluteFilesAndDeduplicatesThem() {
        val message = ConversationMessage("m", "assistant", """
            Download [APK](</Users/me/build/my app.apk>) or `/tmp/report.pdf:12:3`.
            Also /Users/me/build/result.zip.
            Again `/tmp/report.pdf`.
        """.trimIndent())
        assertEquals(listOf("/Users/me/build/my app.apk", "/tmp/report.pdf", "/Users/me/build/result.zip"), message.remoteFiles().map { it.path })
        assertEquals("my app.apk", message.remoteFiles().first().fileName)
    }
    @Test fun excludesCommandsDirectoriesUrlsAndAmbiguousPaths() {
        val text = """
            ```sh
            cat /tmp/private.txt
            ```
            ~~~shell
            adb install /tmp/a.apk
            ~~~
            `adb install /tmp/b.apk`
            adb install /tmp/c.apk
            https://example.test/tmp/d.apk
            [web](https://example.test/tmp/e.apk)
            `/tmp/dir/` `/tmp/.../fake.apk` `/tmp/folder` `/tmp/a*.apk`
            /tmp/dir/ and //example.test/a.apk and /tmp/folder
        """.trimIndent()
        assertTrue(ConversationMessage("m", "assistant", text).remoteFiles().isEmpty())
        assertTrue(ConversationMessage("m", "user", "/tmp/result.apk").remoteFiles().isEmpty())
    }
    @Test fun structuredAttachmentsRemainAvailableForAllRoles() {
        val attachment = RemoteFileReference(attachmentId = "a.apk", fileName = "app.apk", mimeType = "application/vnd.android.package-archive", sizeBytes = 42)
        assertEquals(listOf(attachment), ConversationMessage("m", "user", "", attachments = listOf(attachment)).remoteFiles())
    }
}
