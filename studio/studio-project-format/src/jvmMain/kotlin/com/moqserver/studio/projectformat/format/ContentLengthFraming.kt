package com.moqserver.studio.projectformat.format

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * `Content-Length`-framed message I/O — the same envelope LSP uses. Mirrors
 * `server/Sources/MoqFormatServiceRun/ContentLengthFraming.swift`; both sides of this pipe must
 * agree on the framing, so this stays a direct port rather than a from-scratch reimplementation.
 */
object ContentLengthFraming {
    /** Reads one framed message, or returns null at clean EOF between messages. */
    fun readMessage(input: BufferedInputStream): ByteArray? {
        val headerBytes = java.io.ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                if (headerBytes.size() == 0) return null
                throw EOFException("Stream closed mid-header")
            }
            headerBytes.write(byte)
            val bytes = headerBytes.toByteArray()
            val n = bytes.size
            if (n >= 4 && bytes[n - 4] == 0x0D.toByte() && bytes[n - 3] == 0x0A.toByte() &&
                bytes[n - 2] == 0x0D.toByte() && bytes[n - 1] == 0x0A.toByte()
            ) {
                break
            }
        }

        val headerText = String(headerBytes.toByteArray(), StandardCharsets.UTF_8)
        val contentLength = headerText.split("\r\n")
            .mapNotNull { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2 && parts[0].trim().equals("Content-Length", ignoreCase = true)) {
                    parts[1].trim().toIntOrNull()
                } else {
                    null
                }
            }
            .firstOrNull() ?: throw IllegalStateException("Missing Content-Length header")

        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n == -1) throw EOFException("Stream closed mid-body")
            read += n
        }
        return body
    }

    fun writeMessage(body: ByteArray, output: OutputStream) {
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
        output.write(header)
        output.write(body)
        output.flush()
    }
}
