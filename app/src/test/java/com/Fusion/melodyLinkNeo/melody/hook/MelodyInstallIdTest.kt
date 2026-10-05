package com.fusion.melodyLinkNeo.melody.hook

import android.content.pm.ApplicationInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** M6 install fingerprint: stable for one APK, different the moment the file changes. */
class MelodyInstallIdTest {

    private fun tempApk(): File = File.createTempFile("melody-host", ".apk").apply {
        writeText("payload")
        deleteOnExit()
    }

    private fun infoFor(vararg paths: File): ApplicationInfo = ApplicationInfo().apply {
        sourceDir = paths.first().absolutePath
        if (paths.size > 1) splitSourceDirs = paths.drop(1).map { it.absolutePath }.toTypedArray()
    }

    @Test
    fun sameApk_isStable() {
        val apk = tempApk()
        val info = infoFor(apk)

        assertEquals(MelodyInstallId.of(info), MelodyInstallId.of(info))
    }

    @Test
    fun sizeChange_changesFingerprint() {
        val apk = tempApk()
        val before = MelodyInstallId.of(infoFor(apk))
        apk.appendText("more")

        assertNotEquals(before, MelodyInstallId.of(infoFor(apk)))
    }

    @Test
    fun mtimeChange_changesFingerprint() {
        val apk = tempApk()
        val before = MelodyInstallId.of(infoFor(apk))
        apk.setLastModified(apk.lastModified() + 10_000)

        assertNotEquals(before, MelodyInstallId.of(infoFor(apk)))
    }

    @Test
    fun splitsParticipateInTheFingerprint() {
        val base = tempApk()
        val config = tempApk()

        assertNotEquals(MelodyInstallId.of(infoFor(base)), MelodyInstallId.of(infoFor(base, config)))
    }

    @Test
    fun missingApplicationInfo_isUnknown() {
        assertEquals("unknown", MelodyInstallId.of(null))
    }
}
