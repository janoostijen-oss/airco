package nl.jan.airco.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [await]: resultaat, fouten, en vooral: annuleren breekt het verzoek echt af. */
class HttpAwaitTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { runCatching { server.shutdown() } }

    private fun call() = client.newCall(Request.Builder().url(server.url("/context")).build())

    @Test
    fun geeftStatusEnBodyTerug() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{\"ok\":true}"))
        val result = call().await()
        assertEquals(HttpResult(201, "{\"ok\":true}"), result)
    }

    @Test
    fun foutstatusIsGeenException() = runBlocking {
        // 401/429 e.d. worden door MelcloudApi zelf afgehandeld; await geeft ze gewoon door.
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, call().await().code)
    }

    @Test
    fun netwerkfoutWordtIOException() = runBlocking {
        val c = call()
        server.shutdown()
        try {
            c.await()
            fail("verwacht IOException")
        } catch (e: IOException) {
            // goed
        }
    }

    @Test
    fun annulerenBreektLopendVerzoekAf() = runBlocking {
        // Server antwoordt pas na 30 s: zonder echte annulering zou dit blijven hangen.
        server.enqueue(MockResponse().setHeadersDelay(30, TimeUnit.SECONDS).setBody("laat"))
        val c = call()
        val job = async(Dispatchers.Default) { c.await() }
        server.takeRequest(5, TimeUnit.SECONDS) ?: fail("verzoek kwam niet aan")
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue("OkHttp-call is niet geannuleerd", c.isCanceled())
    }
}
