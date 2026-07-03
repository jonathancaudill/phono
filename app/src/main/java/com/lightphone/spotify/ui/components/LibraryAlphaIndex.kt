package com.lightphone.spotify.ui.components

data class AlphaSection(
    val letter: Char,
    val startIndex: Int,
)

data class LibraryAlphaIndex(
    val letters: List<Char>,
    val sections: List<AlphaSection>,
) {
    val isEmpty: Boolean get() = sections.isEmpty()

    companion object {
        val Empty = LibraryAlphaIndex(letters = emptyList(), sections = emptyList())
    }
}

fun <T> buildLibraryAlphaIndex(
    items: List<T>,
    name: (T) -> String,
): LibraryAlphaIndex {
    if (items.isEmpty()) return LibraryAlphaIndex.Empty

    val sections = mutableListOf<AlphaSection>()
    var lastLetter: Char? = null

    items.forEachIndexed { index, item ->
        val raw = name(item).trim()
        val letter = raw.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
        if (letter != lastLetter) {
            sections.add(AlphaSection(letter = letter, startIndex = index))
            lastLetter = letter
        }
    }

    if (sections.isEmpty()) return LibraryAlphaIndex.Empty

    val letters = sections.map { it.letter }.distinct()
    return LibraryAlphaIndex(letters = letters, sections = sections)
}

fun LibraryAlphaIndex.sectionNearScrollIndex(scrollIndex: Int): AlphaSection? {
    if (sections.isEmpty()) return null
    return sections.lastOrNull { it.startIndex <= scrollIndex } ?: sections.first()
}
