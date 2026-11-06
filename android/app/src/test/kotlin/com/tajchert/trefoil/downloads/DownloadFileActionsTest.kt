package com.tajchert.trefoil.downloads

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadFileActionsTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val apk = CompletedDownload(Uri.parse("content://media/external/downloads/1"), "fixture.APK", "application/octet-stream", 4)

    @Test fun apkInstallerNeedsExplicitPermissionAndUsesReadGrantedContentUri() {
        val actions = DownloadFileActions(application)
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        assertFalse(actions.canInstallPackages())
        assertThrows(IllegalStateException::class.java) { actions.installIntent(apk) }
        assertThrows(IllegalStateException::class.java) { actions.openIntent(apk) }
        val settings = actions.unknownSourcesIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
        assertEquals("package:${application.packageName}", settings.data.toString())
        shadowOf(application.packageManager).setCanRequestPackageInstalls(true)
        val install = actions.installIntent(apk)
        assertEquals(Intent.ACTION_VIEW, install.action)
        assertEquals(DownloadFileActions.APK_MIME, install.type)
        assertEquals(apk.uri, install.data)
        assertEquals(apk.uri, install.clipData!!.getItemAt(0).uri)
        assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, install.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun genericOpenAndShareUseChoosersAndOnlyReadGrants() {
        val actions = DownloadFileActions(application)
        val file = apk.copy(displayName = "fixture.pdf", mimeType = "application/pdf")
        val chooser = actions.openIntent(file)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val open = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_VIEW, open.action)
        assertEquals(file.uri, open.data)
        assertEquals("application/pdf", open.type)
        assertTrue(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val share = actions.shareIntent(file).getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals(file.uri, share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(file.uri, share.clipData!!.getItemAt(0).uri)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertThrows(IllegalArgumentException::class.java) { actions.shareIntent(file.copy(uri = Uri.parse("file:///private/file.pdf"))) }
    }

    @Test fun filenamesStayLeafNamesAndPreserveApkExtensionWithoutControlCharacters() {
        assertEquals("release.apk", safeDownloadName("../../build/release.apk"))
        assertEquals("release.apk", safeDownloadName("..\\..\\release.apk"))
        assertEquals("download", safeDownloadName(".."))
        assertEquals("bad___name.apk", safeDownloadName("bad\n\u202E:name.apk"))
        val long = safeDownloadName("a".repeat(300) + ".apk")
        assertEquals(160, long.length)
        assertTrue(long.endsWith(".apk"))
        assertEquals(DownloadFileActions.APK_MIME, downloadMimeType("release.APK", "text/plain"))
        assertEquals("application/pdf", downloadMimeType("fixture.pdf", "application/octet-stream"))
    }
}
