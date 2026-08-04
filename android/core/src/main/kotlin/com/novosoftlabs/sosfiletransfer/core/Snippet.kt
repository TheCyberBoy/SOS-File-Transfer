package com.novosoftlabs.sosfiletransfer.core

// Port of shared/snippet.ts — a text snippet rides the same optical container
// a file does, as UTF-8 bytes with a media type the receiver recognises.

const val SNIPPET_MEDIA_TYPE = "application/vnd.novosoftlabs.snippet"
const val SNIPPET_FILE_NAME = "snippet.txt"

/** Same hard ceiling reasoning as snippet.ts: MAX_FILE_BYTES is the real
 *  limit, this is a UX cap so a huge paste doesn't bog down a text field. */
const val MAX_SNIPPET_BYTES = 4 * 1024 * 1024
val MAX_SNIPPET_LABEL = "${MAX_SNIPPET_BYTES / 1024 / 1024} MB"

fun isSnippet(file: OpticalFile): Boolean = file.type == SNIPPET_MEDIA_TYPE

fun packSnippet(text: String): PackedOpticalFile {
    if (text.isBlank()) throw IllegalArgumentException("Paste or type some text before sending.")
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_SNIPPET_BYTES) {
        throw IllegalArgumentException("Text snippets are limited to $MAX_SNIPPET_LABEL.")
    }
    return packFile(SNIPPET_FILE_NAME, SNIPPET_MEDIA_TYPE, bytes)
}

/** Decode an already-unpacked, already-verified snippet container. */
fun snippetText(file: OpticalFile): String {
    if (!isSnippet(file)) throw IllegalArgumentException("This stream is not a text snippet.")
    return try {
        val decoder = Charsets.UTF_8.newDecoder()
        decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(file.bytes)).toString()
    } catch (e: Exception) {
        throw IllegalArgumentException("The recovered snippet is not valid UTF-8.")
    }
}
