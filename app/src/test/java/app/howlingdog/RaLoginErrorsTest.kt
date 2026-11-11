package app.howlingdog

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the RetroAchievements login error fix
 * (v1.1.1): a rejected login's error body is no longer thrown
 * away, a transport failure names its cause, and the Settings
 * screen never shows a bare "No response" again.
 */
class RaLoginErrorsTest {

    @Test
    fun failureStatusMapsTimeoutTo524() {
        assertEquals(524, RaLoginErrors.failureStatus(SocketTimeoutException("timed out")))
    }

    @Test
    fun failureStatusMapsUnreachableTo521() {
        assertEquals(521, RaLoginErrors.failureStatus(UnknownHostException("no such host")))
        assertEquals(521, RaLoginErrors.failureStatus(ConnectException("refused")))
        assertEquals(521, RaLoginErrors.failureStatus(IOException("broken pipe")))
    }

    @Test
    fun failureStatusMapsOtherExceptionsToZero() {
        assertEquals(0, RaLoginErrors.failureStatus(IllegalStateException("boom")))
    }

    @Test
    fun describeMapsInvalidCredentials() {
        assertEquals(
            "Invalid username or API key. Check both in your RetroAchievements account settings.",
            RaLoginErrors.describe("Invalid user/token combination."),
        )
    }

    @Test
    fun describeMapsNoResponseToUnreachable() {
        val expected =
            "Could not reach the RetroAchievements server. Check your connection and try again."
        assertEquals(expected, RaLoginErrors.describe("No response"))
        assertEquals(expected, RaLoginErrors.describe(null))
        assertEquals(expected, RaLoginErrors.describe(""))
        assertEquals(expected, RaLoginErrors.describe("   "))
    }

    @Test
    fun describeMapsTimeoutAndConnectFailures() {
        assertEquals(
            "The server did not answer in time. Try again.",
            RaLoginErrors.describe("Request has timed out."),
        )
        assertEquals(
            "Could not connect to the RetroAchievements server. Check your connection and try again.",
            RaLoginErrors.describe("Could not connect to server."),
        )
    }

    @Test
    fun describePassesUnknownMessagesThrough() {
        assertEquals("Something odd happened.", RaLoginErrors.describe("Something odd happened."))
    }

    /**
     * A fake connection (no network): the response code and the
     * two streams are scripted, so the test runs on the plain
     * JVM and in CI with no server.
     */
    private class FakeConnection(
        private val code: Int,
        private val body: ByteArray?,
        private val errorBody: ByteArray?,
    ) : HttpURLConnection(URL("http://127.0.0.1/")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getInputStream(): InputStream =
            body?.let { ByteArrayInputStream(it) } ?: throw IOException("no body")

        override fun getErrorStream(): InputStream? =
            errorBody?.let { ByteArrayInputStream(it) }
    }

    /**
     * The v1.1.0 defect: a 401 whose body carries the server's
     * JSON error must come back with its body, not be discarded
     * as an empty body. (The real endpoint answers a rejected
     * login exactly this way: 401 +
     * {"Success":false,...,"Error":"Invalid user/token
     * combination."}.)
     */
    @Test
    fun readResponseKeepsNon200ErrorBody() {
        val errorJson =
            "{\"Success\":false,\"Status\":401,\"Code\":\"invalid_credentials\",\"Error\":\"Invalid user/token combination.\"}"
        val conn = FakeConnection(401, null, errorJson.toByteArray(Charsets.UTF_8))
        val (body, code) = RaLoginErrors.readResponse(conn)
        assertEquals(401, code)
        assertEquals(errorJson, String(body, Charsets.UTF_8))
        assertTrue(String(body, Charsets.UTF_8).contains("Invalid user/token combination."))
    }

    @Test
    fun readResponseReturns200Body() {
        val conn = FakeConnection(200, "{\"Success\":true}".toByteArray(Charsets.UTF_8), null)
        val (body, code) = RaLoginErrors.readResponse(conn)
        assertEquals(200, code)
        assertEquals("{\"Success\":true}", String(body, Charsets.UTF_8))
    }

    @Test
    fun readResponseWithNoErrorBodyIsEmpty() {
        val (body, code) = RaLoginErrors.readResponse(FakeConnection(500, null, null))
        assertEquals(500, code)
        assertEquals(0, body.size)
    }
}
