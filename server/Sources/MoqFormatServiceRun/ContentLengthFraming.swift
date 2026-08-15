import Foundation

/// `Content-Length`-framed message I/O over stdio — the same envelope LSP uses, chosen so a
/// future LSP adapter over `MoqService` can share this framing rather than invent its own, and so
/// payload bytes never need to dodge a newline delimiter the way line-delimited JSON would.
///
/// Fixture and import content never crosses this wire (see `FixturePathResolver` / the
/// `body_file` contract): callers pass paths, not bytes, so this framing only ever needs to carry
/// JSON, never raw binary.
enum ContentLengthFraming {
    struct FramingError: Error, CustomStringConvertible {
        let description: String
    }

    /// Reads one framed message from `input`, or returns nil at clean EOF (no partial headers
    /// read yet) — the normal way this loop ends when the peer closes its side of the pipe.
    static func readMessage(from input: FileHandle) throws -> Data? {
        var headerBytes = Data()

        // Headers are ASCII and terminated by a bare CRLF line; read byte-by-byte until the
        // blank line, same approach LSP servers use since Content-Length is the only header that
        // matters here.
        while true {
            guard let byte = readByte(from: input) else {
                if headerBytes.isEmpty {
                    // Clean EOF between messages — the normal way this loop ends.
                    return nil
                }
                throw FramingError(description: "Stream closed mid-header")
            }
            headerBytes.append(byte)
            if headerBytes.count >= 4,
                headerBytes.suffix(4).elementsEqual([0x0D, 0x0A, 0x0D, 0x0A])
            {
                break
            }
        }

        guard let headerText = String(data: headerBytes, encoding: .utf8) else {
            throw FramingError(description: "Header block is not valid UTF-8")
        }
        var contentLength: Int?
        for line in headerText.split(separator: "\r\n") {
            let parts = line.split(separator: ":", maxSplits: 1)
            guard parts.count == 2 else { continue }
            let name = parts[0].trimmingCharacters(in: .whitespaces)
            let value = parts[1].trimmingCharacters(in: .whitespaces)
            if name.caseInsensitiveCompare("Content-Length") == .orderedSame {
                contentLength = Int(value)
            }
        }
        guard let length = contentLength else {
            throw FramingError(description: "Missing Content-Length header")
        }

        var body = Data()
        body.reserveCapacity(length)
        while body.count < length {
            let chunk = input.readData(ofLength: length - body.count)
            if chunk.isEmpty { throw FramingError(description: "Stream closed mid-body") }
            body.append(chunk)
        }
        return body
    }

    /// Writes one framed message to `output`. A single call is not safe to interleave with
    /// another in-flight write from a different thread — the dispatcher serializes writes itself.
    static func writeMessage(_ body: Data, to output: FileHandle) throws {
        var framed = Data("Content-Length: \(body.count)\r\n\r\n".utf8)
        framed.append(body)
        output.write(framed)
    }

    private static func readByte(from input: FileHandle) -> UInt8? {
        input.readData(ofLength: 1).first
    }
}
