package org.hpb.cvat

import java.io.ByteArrayOutputStream

/**
 * The smallest multipart/form-data writer that satisfies CVAT's frame upload.
 * The JDK's HttpClient has no encoder of its own, and pulling a whole HTTP
 * library in for one endpoint is not worth it.
 */
class Multipart(private val boundary: String = "hpbCvatBoundary") {
    private val out = ByteArrayOutputStream()

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    fun field(name: String, value: String) = apply {
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        out.write("$value\r\n".toByteArray())
    }

    fun file(name: String, filename: String, bytes: ByteArray, type: String = "image/png") = apply {
        out.write("--$boundary\r\n".toByteArray())
        out.write(
            "Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n".toByteArray(),
        )
        out.write("Content-Type: $type\r\n\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n".toByteArray())
    }

    fun body(): ByteArray {
        val closed = ByteArrayOutputStream()
        closed.write(out.toByteArray())
        closed.write("--$boundary--\r\n".toByteArray())
        return closed.toByteArray()
    }
}
