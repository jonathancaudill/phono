package com.lightphone.spotify.data.webapi

data class LibraryPage<T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
)
