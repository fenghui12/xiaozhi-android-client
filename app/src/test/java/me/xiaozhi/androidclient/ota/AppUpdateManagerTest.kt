package me.xiaozhi.androidclient.ota

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdateManagerTest {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private lateinit var httpClient: OkHttpClient
    private var mockResponseBody = ""

    @Before
    fun setUp() {
        httpClient = OkHttpClient.Builder().build()
        serverSocket = ServerSocket(0)
        executor.execute {
            while (serverSocket?.isClosed == false) {
                try {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use {
            val input = it.getInputStream().bufferedReader()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }
            val bodyBytes = mockResponseBody.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            it.getOutputStream().write(response.toByteArray())
            it.getOutputStream().write(bodyBytes)
            it.getOutputStream().flush()
        }
    }

    @After
    fun tearDown() {
        serverSocket?.close()
        serverSocket = null
    }

    @Test
    fun checkForUpdate_hasNewVersion_returnsHasUpdate() = runBlocking {
        mockResponseBody = """
            {
              "versionCode": 4,
              "versionName": "1.2.0",
              "downloadUrl": "http://127.0.0.1:${serverSocket!!.localPort}/app.apk",
              "sha256": "fake-hash",
              "releaseNotes": "新功能更新",
              "forceUpdate": false
            }
        """.trimIndent()

        val dummyContext = android.app.Application()
        val updateManager = AppUpdateManager(
            context = dummyContext,
            baseHttpClient = httpClient,
            updateIndexUrl = "http://127.0.0.1:${serverSocket!!.localPort}/version.json",
        )

        val result = updateManager.checkForUpdate(currentVersionCode = 3)
        assertTrue("Expected HasUpdate but got $result", result is UpdateCheckResult.HasUpdate)
        val hasUpdate = result as UpdateCheckResult.HasUpdate
        assertEquals(4, hasUpdate.info.versionCode)
        assertEquals("1.2.0", hasUpdate.info.versionName)
        assertEquals("新功能更新", hasUpdate.info.releaseNotes)
    }

    @Test
    fun checkForUpdate_alreadyLatest_returnsUpToDate() = runBlocking {
        mockResponseBody = """
            {
              "versionCode": 3,
              "versionName": "1.1.0",
              "downloadUrl": "http://127.0.0.1:${serverSocket!!.localPort}/app.apk",
              "sha256": "fake-hash"
            }
        """.trimIndent()

        val dummyContext = android.app.Application()
        val updateManager = AppUpdateManager(
            context = dummyContext,
            baseHttpClient = httpClient,
            updateIndexUrl = "http://127.0.0.1:${serverSocket!!.localPort}/version.json",
        )

        val result = updateManager.checkForUpdate(currentVersionCode = 3)
        assertTrue("Expected UpToDate but got $result", result is UpdateCheckResult.UpToDate)
    }
}
