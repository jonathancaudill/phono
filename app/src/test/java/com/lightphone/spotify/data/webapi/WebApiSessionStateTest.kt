package com.lightphone.spotify.data.webapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebApiSessionStateTest {

    @Test
    fun dataObjects_areSingletonsEqualToThemselves() {
        assertEquals(WebApiSessionState.NotConfigured, WebApiSessionState.NotConfigured)
        assertEquals(WebApiSessionState.Authorized, WebApiSessionState.Authorized)
        assertEquals(WebApiSessionState.Expired, WebApiSessionState.Expired)
    }

    @Test
    fun distinctStates_areNotEqual() {
        assertNotEquals<WebApiSessionState>(WebApiSessionState.NotConfigured, WebApiSessionState.Authorized)
        assertNotEquals<WebApiSessionState>(WebApiSessionState.Authorized, WebApiSessionState.Expired)
        assertNotEquals<WebApiSessionState>(WebApiSessionState.NotConfigured, WebApiSessionState.Expired)
    }

    @Test
    fun whenExpression_exhaustivelyMapsEveryState() {
        fun describe(state: WebApiSessionState): String = when (state) {
            WebApiSessionState.NotConfigured -> "not-configured"
            WebApiSessionState.Authorized -> "authorized"
            WebApiSessionState.Expired -> "expired"
        }

        assertEquals("not-configured", describe(WebApiSessionState.NotConfigured))
        assertEquals("authorized", describe(WebApiSessionState.Authorized))
        assertEquals("expired", describe(WebApiSessionState.Expired))
    }

    @Test
    fun isInstanceChecks_workAsExpectedForUiGating() {
        val state: WebApiSessionState = WebApiSessionState.Authorized
        assertTrue(state is WebApiSessionState.Authorized)
        assertTrue(state !is WebApiSessionState.Expired)
        assertTrue(state !is WebApiSessionState.NotConfigured)
    }
}