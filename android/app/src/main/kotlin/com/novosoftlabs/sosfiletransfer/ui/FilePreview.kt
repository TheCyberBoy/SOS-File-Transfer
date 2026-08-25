package com.novosoftlabs.sosfiletransfer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView

/**
 * What kind of in-app preview a received file's MIME type can get.
 *
 * Deliberately doesn't try to cover every format — Word/Excel/PowerPoint
 * (.docx/.xlsx/.pptx) have no built-in Android renderer, and building one
 * from scratch means either a heavy proprietary library or a cloud
 * conversion API, the latter of which would break this app's entire
 * no-network premise. Those fall through to NONE and rely on the "Open"
 * button (ACTION_VIEW) handing off to whatever app the OS considers the
 * right viewer, which is the honest answer for that class of format.
 */
enum class PreviewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, NONE }

fun previewKindFor(mimeType: String): PreviewKind = when {
    mimeType.startsWith("image/") -> PreviewKind.IMAGE
    mimeType.startsWith("video/") -> PreviewKind.VIDEO
    mimeType.startsWith("audio/") -> PreviewKind.AUDIO
    mimeType == "application/pdf" -> PreviewKind.PDF
    mimeType.startsWith("text/") ||
        mimeType in setOf("application/json", "application/xml", "application/x-yaml") -> PreviewKind.TEXT
    else -> PreviewKind.NONE
}

/** How much of a text file to actually decode and show — this is a quick
 *  look, not a text editor, and a multi-megabyte log file has no business
 *  being pulled fully into a Compose Text node. */
private const val TEXT_PREVIEW_CAP_BYTES = 32 * 1024

data class FilePreview(
    val kind: PreviewKind,
    val bitmap: Bitmap? = null,
    val caption: String? = null,
    val text: String? = null,
    val textTruncated: Boolean = false,
)

/** Builds whatever preview is possible for a just-saved file. Runs on
 *  whatever dispatcher the caller is already on (the receiver calls this
 *  from Dispatchers.Default) — PdfRenderer and Bitmap decode are real CPU
 *  work, never main-thread material. */
fun buildFilePreview(context: Context, mimeType: String, bytes: ByteArray, savedUri: Uri): FilePreview {
    val kind = previewKindFor(mimeType)
    return when (kind) {
        PreviewKind.IMAGE -> {
            val bitmap = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Throwable) {
                Log.e("FilePreview", "Failed to decode image preview", e)
                null
            }
            FilePreview(kind, bitmap = bitmap)
        }
        PreviewKind.PDF -> renderPdfFirstPage(context, savedUri)?.let { (bitmap, caption) ->
            FilePreview(kind, bitmap = bitmap, caption = caption)
        } ?: FilePreview(PreviewKind.NONE)
        PreviewKind.TEXT -> {
            val truncated = bytes.size > TEXT_PREVIEW_CAP_BYTES
            val text = String(bytes, 0, minOf(TEXT_PREVIEW_CAP_BYTES, bytes.size), Charsets.UTF_8)
            FilePreview(kind, text = text, textTruncated = truncated)
        }
        // Video/audio don't need anything precomputed — MediaPreviewPlayer
        // below plays directly from the saved content Uri.
        PreviewKind.VIDEO, PreviewKind.AUDIO, PreviewKind.NONE -> FilePreview(kind)
    }
}

/** Only the first page — a full pager is a bigger UI investment than a
 *  "does this look right" preview needs, and the caption makes clear
 *  there's more to see via the Open button for anything beyond page 1. */
private fun renderPdfFirstPage(context: Context, uri: Uri): Pair<Bitmap, String?>? {
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    // PDF pages render with a transparent background by
                    // default — white it out first or dark-theme surfaces
                    // show through as black.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val caption = if (renderer.pageCount > 1) "Page 1 of ${renderer.pageCount}" else null
                    bitmap to caption
                }
            }
        }
    } catch (e: Throwable) {
        Log.e("FilePreview", "Failed to render PDF preview", e)
        null
    }
}

/** Inline video/audio playback via Media3 — same AndroidView-wrapping
 *  pattern as CameraPreview's PreviewView. Plays straight from the
 *  MediaStore content Uri saveToDownloads() already wrote; no separate
 *  temp file or extra permission needed since an app always has implicit
 *  read access to media it created itself via MediaStore. */
@Composable
fun MediaPreviewPlayer(uri: Uri, isAudioOnly: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        modifier = if (isAudioOnly) modifier.fillMaxWidth().height(72.dp) else modifier.fillMaxWidth(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
    )
}

/** Bounded-height, independently-scrollable text preview — the screen
 *  itself already scrolls, so a large file gets its own inner scroll
 *  region instead of stretching the whole page. */
@Composable
fun TextFilePreview(text: String, truncated: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    )
    if (truncated) {
        Text(
            "Preview truncated — open the file to see the rest.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
