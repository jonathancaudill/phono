package com.lightphone.spotify.data.native

/** Step 1 librespot session is required but not connected. */
class NativeSessionRequiredException :
    Exception("Sign in to Spotify playback to load playlists and artists.")
