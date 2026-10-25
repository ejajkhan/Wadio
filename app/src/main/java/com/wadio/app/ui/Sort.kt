package com.wadio.app.ui

import com.wadio.app.MediaSortMode

fun sortLabel(mode: MediaSortMode): String = when (mode) {
    MediaSortMode.Name -> "Name"
    MediaSortMode.Size -> "Size"
    MediaSortMode.DateCreated -> "Date created"
}
