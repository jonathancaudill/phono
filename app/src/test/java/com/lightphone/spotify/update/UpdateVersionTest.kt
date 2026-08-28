package com.lightphone.spotify.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun `tag prefix is ignored`() {
        assertTrue(isNewerVersion("v0.1.5", "0.1.4"))
        assertFalse(isNewerVersion("v0.1.4", "0.1.4"))
    }

    @Test
    fun `components compare numerically not lexically`() {
        assertTrue(isNewerVersion("0.1.10", "0.1.9"))
        assertTrue(isNewerVersion("0.2.0", "0.1.99"))
        assertFalse(isNewerVersion("0.1.9", "0.1.10"))
    }

    @Test
    fun `missing components count as zero`() {
        assertTrue(isNewerVersion("0.2", "0.1.4"))
        assertFalse(isNewerVersion("0.1", "0.1.0"))
        assertTrue(isNewerVersion("0.1.0.1", "0.1"))
    }

    @Test
    fun `unparseable versions never trigger an update`() {
        assertFalse(isNewerVersion("nightly", "0.1.4"))
        assertFalse(isNewerVersion("0.1.4", "dev"))
        assertFalse(isNewerVersion("", "0.1.4"))
    }

    @Test
    fun `prerelease suffix is stripped before comparing`() {
        assertTrue(isNewerVersion("0.2.0-rc1", "0.1.4"))
        assertFalse(isNewerVersion("0.1.4-rc1", "0.1.4"))
    }

    @Test
    fun `check is due after the interval or if the clock moved backwards`() {
        val now = 1_700_000_000_000L
        assertTrue(UpdatePreferences.isDue(now, lastCheckMs = 0L))
        assertTrue(UpdatePreferences.isDue(now, lastCheckMs = now - UpdatePreferences.CHECK_INTERVAL_MS))
        assertFalse(UpdatePreferences.isDue(now, lastCheckMs = now - 1))
        assertTrue(UpdatePreferences.isDue(now, lastCheckMs = now + 1))
    }
}
