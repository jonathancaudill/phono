package com.lightphone.spotify.ui

import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "OAuthLoopback"
private const val HOST = "127.0.0.1"
private const val PORT = 8898

/**
 * Captures Spotify Step 1 OAuth at `http://127.0.0.1:8898/login?code=…`.
 *
 * The WebView often never fires [android.webkit.WebViewClient.shouldOverrideUrlLoading]
 * after email-OTP (authorize SPA stalls). When Spotify finally redirects, the GET
 * hits this loopback server — same pattern as librespot-oauth on desktop.
 */
object SpotifyOAuthLoopback {
    @Volatile
    private var serverThread: Thread? = null

    private val running = AtomicBoolean(false)

    fun start(onAuthorizationCode: (code: String, state: String?) -> Unit) {
        stop()
        running.set(true)
        serverThread = thread(name = "spotify-oauth-loopback", isDaemon = true) {
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(HOST, PORT))
                Log.e(TAG, "listening on $HOST:$PORT")
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        server.soTimeout = 800
                        handleConnection(server.accept(), onAuthorizationCode)
                    } catch (_: java.net.SocketTimeoutException) {
                        // wake to check running flag
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "server failed: ${e.message}", e)
                }
            } finally {
                runCatching { server.close() }
            }
        }
    }

    fun stop() {
        running.set(false)
        serverThread?.interrupt()
        serverThread = null
    }

    private fun handleConnection(
        socket: Socket,
        onAuthorizationCode: (code: String, state: String?) -> Unit,
    ) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val pathAndQuery = parts[1]
            Log.e(TAG, "request $pathAndQuery")
            val uri = Uri.parse("http://$HOST:$PORT$pathAndQuery")
            if (!pathAndQuery.startsWith("/login")) {
                writeResponse(s, 404, "Not Found")
                return
            }
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            writeResponse(s, 200, "Signed in — return to Phono")
            if (code != null) {
                Log.e(TAG, "redirect captured code=${code.take(8)}… state=${state != null}")
                onAuthorizationCode(code, state)
            }
        }
    }

    private fun writeResponse(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers =
            "HTTP/1.1 $status OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
