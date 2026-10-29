package com.droiddock.app.ui.files

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droiddock.app.ui.components.DockIconBadge
import com.droiddock.app.ui.components.Tone
import com.droiddock.app.ui.theme.FileKind

/**
 * File-type badge: an icon in a tonal container. Colour comes from three
 * theme roles (documents/code = primary, media = tertiary, the rest =
 * secondary), so every style stays coherent; Mono drops colour entirely.
 */
@Composable
internal fun FileBadge(name: String, modifier: Modifier = Modifier, isDir: Boolean = false, size: Dp = 40.dp) {
    if (isDir) {
        DockIconBadge(Icons.Rounded.Folder, modifier, tone = Tone.PRIMARY, size = size)
        return
    }
    val kind = FileKind.of(name)
    DockIconBadge(fileKindIcon(kind), modifier, tone = fileKindTone(kind), size = size)
}

internal fun fileKindTone(kind: FileKind): Tone = when (kind) {
    FileKind.DOCUMENT, FileKind.CODE -> Tone.PRIMARY
    FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO -> Tone.TERTIARY
    FileKind.ARCHIVE, FileKind.APP, FileKind.OTHER -> Tone.SECONDARY
}

internal fun fileKindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.DOCUMENT -> Icons.Rounded.Description
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.VIDEO -> Icons.Rounded.Videocam
    FileKind.AUDIO -> Icons.Rounded.MusicNote
    FileKind.ARCHIVE -> Icons.Rounded.FolderZip
    FileKind.CODE -> Icons.Rounded.Code
    FileKind.APP -> Icons.Rounded.Android
    FileKind.OTHER -> Icons.Rounded.InsertDriveFile
}
