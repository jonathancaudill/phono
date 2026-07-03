package com.lightphone.spotify.ui.light

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.graphics.vector.ImageVector
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons

/** Maps Material icons to LightIcons during incremental migration. */
fun lightIconFor(material: ImageVector): LightIconConfiguration? = when (material) {
    Icons.AutoMirrored.Filled.ArrowBack,
    Icons.AutoMirrored.Filled.ArrowBackIos,
    -> LightIcons.BACK
    Icons.Filled.Search -> LightIcons.SEARCH
    Icons.Filled.Shuffle -> LightIcons.SHUFFLE
    Icons.Filled.PlayArrow -> LightIcons.PLAY
    Icons.Filled.Pause -> LightIcons.PAUSE
    Icons.Filled.SkipPrevious -> LightIcons.REWIND
    Icons.Filled.SkipNext -> LightIcons.FAST_FORWARD
    Icons.Filled.Repeat,
    Icons.Filled.RepeatOne,
    -> LightIcons.LOOP
    Icons.Filled.Add -> LightIcons.ADD
    Icons.Filled.Edit -> LightIcons.PENCIL
    Icons.Filled.Check -> LightIcons.ACCEPT
    Icons.Filled.Close, Icons.Filled.Clear -> LightIcons.CLOSE
    Icons.Filled.PlaylistPlay,
    Icons.AutoMirrored.Filled.PlaylistPlay,
    -> LightIcons.LIST
    Icons.Filled.PlaylistAdd -> LightIcons.ADD
    Icons.Filled.MusicNote, Icons.Filled.Album, Icons.Filled.GraphicEq -> LightIcons.MEDIA
    Icons.Filled.MoreHoriz -> LightIcons.ELLIPSES
    Icons.Filled.KeyboardArrowUp -> LightIcons.UP
    Icons.Filled.KeyboardArrowDown -> LightIcons.DOWN
    Icons.AutoMirrored.Filled.KeyboardArrowLeft -> LightIcons.BACK
    Icons.AutoMirrored.Filled.KeyboardArrowRight -> LightIcons.FAST_FORWARD
    else -> null
}
