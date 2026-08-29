package org.hpb.harness

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
/** A throwaway nostr-relay (independent Python implementation) for tests. */
class RelayFixture private constructor(
    val url: String,
    private val process: Process,
) : AutoCloseable {

    companion object {
        fun start(): RelayFixture {
            val port = ServerSocket(0).use { it.localPort }
            val dir = Files.createTempDirectory("hpb-relay")
            val config = dir.resolve("config.yaml")
            Files.writeString(
                config,
                """
                DEBUG: false
                relay_name: hpb test relay
                storage:
                  sqlalchemy.url: sqlite+aiosqlite:///$dir/nostr.sqlite3
                gunicorn:
                  bind: 127.0.0.1:$port
                  workers: 1
                  loglevel: warning
                purple:
                  host: 127.0.0.1
                  port: $port
                """.trimIndent(),
            )
            val process = ProcessBuilder(relayExe(), "-c", config.toString(), "serve")
                .redirectOutput(dir.resolve("relay.log").toFile())
                .redirectErrorStream(true)
                .start()
            val url = "ws://127.0.0.1:$port"
            waitReady(url)
            return RelayFixture(url, process)
        }

        /** The test JVM's PATH may not include pip's user bin; probe known spots. */
        private fun relayExe(): String =
            listOfNotNull(
                System.getenv("NOSTR_RELAY_EXE"),
                "${System.getProperty("user.home")}/.local/bin/nostr-relay",
                "/usr/local/bin/nostr-relay",
            ).firstOrNull { java.io.File(it).canExecute() } ?: "nostr-relay"

        private fun waitReady(url: String, timeoutMillis: Long = 30_000) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                try {
                    val ws = HttpClient.newHttpClient().newWebSocketBuilder()
                        .buildAsync(URI.create(url), object : WebSocket.Listener {}).join()
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "probe").join()
                    return
                } catch (e: Exception) {
                    check(System.currentTimeMillis() <= deadline) { "relay did not start: $e" }
                    Thread.sleep(250)
                }
            }
        }
    }

    override fun close() {
        process.destroy()
        process.waitFor()
    }
}
