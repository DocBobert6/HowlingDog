package app.howlingdog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Phase 10 fix (v1.1.1) — RetroAchievements login errors.
 *
 * The login failed on Robert's device with "No response". The
 * cause, verified against the live endpoint: the RA login
 * endpoint answers a rejected login with HTTP 401 and a JSON
 * body ({"Success":false,...,"Error":"Invalid user/token
 * combination."}), but [RaClient]'s HTTP request discarded the
 * body for every non-200 status, so the native layer got an
 * empty body and rcheevos reported RC_NO_RESPONSE — "No
 * response". The server had answered; the app threw the answer
 * away.
 *
 * This object holds the pure (Android-free) pieces of the fix:
 * reading a response body for every status, classifying a
 * transport failure into the status rcheevos maps to a named
 * error, and turning rcheevos' raw error strings into the plain
 * message the Settings screen shows. Nothing here touches
 * Android, Compose, or coroutines, so it is covered by the
 * pure-test harness (RaLoginErrorsTest).
 */
object RaLoginErrors {

    /**
     * Synthetic statuses handed to rcheevos for a transport
     * failure (no HTTP status exists). rcheevos maps an empty
     * body with these to a named error: 521 → "Could not
     * connect to server.", 524 → "Request has timed out."
     * (see rc_json_parse_server_response in the vendored
     * rcheevos).
     */
    const val STATUS_UNREACHABLE: Int = 521
    const val STATUS_TIMED_OUT: Int = 524

    /**
     * Read one HTTP response, returning the body and the status
     * code. A non-200 answer still carries a body — the server's
     * error (for example the login endpoint's 401 JSON) — so it
     * is read from the error stream, not discarded. The caller
     * classifies a thrown transport failure with [failureStatus].
     */
    fun readResponse(conn: HttpURLConnection): Pair<ByteArray, Int> {
        val code = conn.responseCode
        val stream = if (code == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            ?: return ByteArray(0) to code
        val out = ByteArrayOutputStream()
        stream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray() to code
    }

    /**
     * Map a transport failure to the status handed to rcheevos,
     * so the failure names its cause instead of surfacing as a
     * bare "No response". A timeout is a timeout; a host that
     * cannot be resolved or a refused connection is unreachable;
     * anything else keeps status 0 (rcheevos' generic "No
     * response" fallback).
     */
    fun failureStatus(e: Exception): Int = when (e) {
        is SocketTimeoutException -> STATUS_TIMED_OUT
        is UnknownHostException -> STATUS_UNREACHABLE
        is ConnectException -> STATUS_UNREACHABLE
        is IOException -> STATUS_UNREACHABLE
        else -> 0
    }

    /**
     * Turn a raw rcheevos login error into the message the
     * Settings screen shows. A failure is never silent and never
     * vague: wrong credentials name the credentials, no network
     * names the connection, and a timeout names the server not
     * answering. An unknown message passes through unchanged.
     */
    fun describe(error: String?): String = when (val message = error?.trim().orEmpty()) {
        "",
        "No response" ->
            "Could not reach the RetroAchievements server. Check your connection and try again."

        "Invalid user/password combination. Please try again." ->
            "Invalid username or password. Check both in your RetroAchievements account settings."

        "Invalid user/token combination." ->
            "Your saved login is no longer valid. Log in again with your username and password."

        "Request has timed out." ->
            "The server did not answer in time. Try again."

        "Could not connect to server." ->
            "Could not connect to the RetroAchievements server. Check your connection and try again."

        else -> message
    }

    /**
     * v1.2.0 (bug fix) — whether a raw rcheevos login error
     * means the server explicitly rejected the credentials (an
     * invalid username/password, or an invalid username/token).
     * Only then is the stored session token dead: a rejected
     * token is cleared, so the next launch asks for the password
     * instead of retrying the same token. A transient failure
     * ("No response", a timeout, unreachable) never clears the
     * token — the login simply did not happen, and the next
     * launch retries it. An unknown message is treated the same
     * way (fail closed: keep the token).
     */
    fun isInvalidCredentials(error: String?): Boolean = when (error?.trim()) {
        "Invalid user/password combination. Please try again.",
        "Invalid user/token combination." -> true
        else -> false
    }
}
