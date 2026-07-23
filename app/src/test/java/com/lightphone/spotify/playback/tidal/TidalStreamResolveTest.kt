package com.lightphone.spotify.playback.tidal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalStreamResolveTest {
    @Test
    fun sanitize_stripsNonNumericGroup() {
        val raw = """<AdaptationSet group="main" mimeType="audio/mp4"/>"""
        val out = TidalStreamResolve.sanitizeTidalMpd(raw)
        assertFalse(out.contains("group="))
        assertTrue(out.contains("mimeType=\"audio/mp4\""))
    }

    @Test
    fun clearDashWithoutContentProtection_isNotWidevine() {
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013">
              <Period>
                <AdaptationSet mimeType="audio/mp4" codecs="flac">
                  <Representation bandwidth="2000000"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        assertFalse(TidalStreamResolve.isWidevineDash(mpd))
    }

    @Test
    fun widevineUuid_isDetected() {
        val mpd = """
            <MPD>
              <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed">
                <cenc:pssh>AAAA</cenc:pssh>
              </ContentProtection>
            </MPD>
        """.trimIndent()
        assertTrue(TidalStreamResolve.isWidevineDash(mpd))
    }
}
