package io.github.ddastoor.kindlify

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A minimal HTTP/1.1 server on 127.0.0.1 for tests: serves fixed responses by path,
 * 404 for anything else. One connection per request (Connection: close).
 */
class TinyWebServer(
    private val routes: Map<String, Pair<String, ByteArray>>,
    /** Paths answered with this HTTP status instead (e.g. 403), with an empty page. */
    private val statuses: Map<String, Int> = emptyMap(),
) : AutoCloseable {

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "tiny-web-server") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use(::serve) }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        while (!input.readLine().isNullOrEmpty()) Unit // skip headers
        val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
        val (type, body) = routes[path] ?: ("text/plain" to "not found".toByteArray())
        val status = statuses[path]?.let { "$it Refused" } ?: if (path in routes) "200 OK" else "404 Not Found"
        val out = client.getOutputStream()
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1),
        )
        out.write(body)
        out.flush()
    }

    override fun close() = socket.close()
}
