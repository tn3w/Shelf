package dev.tn3w.shelf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun fdroidClientsAreStoreInstallers() {
        assertTrue(isStoreInstaller("org.fdroid.fdroid"))
        assertTrue(isStoreInstaller("com.looker.droidify"))
    }

    @Test
    fun sideloadsAreNotStoreInstallers() {
        assertFalse(isStoreInstaller(null))
        assertFalse(isStoreInstaller("com.google.android.packageinstaller"))
    }
}
