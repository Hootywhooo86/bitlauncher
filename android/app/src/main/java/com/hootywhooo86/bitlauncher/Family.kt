package com.hootywhooo86.bitlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The 128bit family account: the same sign-in as 128bitFIT, 128bitPlay and Tracker (one
 * Supabase project). bitlauncher uses it for two things only, both opt-in by signing in:
 * backing up your layouts and settings, and reading your family feed for the Today page.
 */
object FamilyConfig {
    const val URL = "https://wvflpwtaszpstmjpkrhu.supabase.co"

    /** The publishable key is public by design; every table sits behind row-level security. */
    const val KEY = "sb_publishable_dO8uP74cTV1tbuqESgxysw_323C7lWh"

    /** bitlauncher's row in the shared per-app settings store. */
    const val APP_KEY = "bitlauncher"

    /** Where Google sign-in comes back to; must be listed in the project's Redirect URLs. */
    const val REDIRECT = "bitlauncher://family-auth"
}

/** Keeps the sign-in encrypted with a key that lives in the phone's secure hardware. */
class SecureStore(context: Context) {
    private val sp = context.getSharedPreferences("family", Context.MODE_PRIVATE)
    private val alias = "bitlauncher-family"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            sp.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        sp.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? = try {
        val sealed = Base64.decode(sp.getString(name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        }
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    } catch (e: Exception) {
        null
    }
}

/** One family-feed event, as get_events returns it. */
data class FamilyEvent(val id: String, val app: String, val type: String, val at: Long, val data: JSONObject)

/** Turning feed events into short lines, and finding open quests. Pure, so it's tested. */
object FamilyFeed {
    fun parse(json: String): List<FamilyEvent> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            FamilyEvent(o.getString("id"), o.optString("app"), o.getString("type"), o.optLong("at"), o.optJSONObject("data") ?: JSONObject())
        }
    }

    private fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }

    /** "Workout · Push day · 45 min". Unknown types still show, from whatever name they carry. */
    fun describe(e: FamilyEvent): String {
        val d = e.data
        return when (e.type) {
            "workout.logged" -> listOfNotNull(
                "Workout",
                d.str("name"),
                d.optInt("minutes").takeIf { it > 0 }?.let { "$it min" },
                d.optDouble("km").takeIf { !it.isNaN() && it > 0 }?.let { "%.1f km".format(it) },
            ).joinToString("  ·  ")
            "health.day" -> d.optInt("steps").takeIf { it > 0 }?.let { "%,d steps".format(it) } ?: "Health day"
            "book.finished" -> "Finished  ·  ${d.str("title") ?: "a book"}"
            "movie.watched" -> "Watched  ·  ${d.str("title") ?: "a movie"}"
            "episode.watched" -> listOfNotNull("Watched", d.str("show"), d.str("episode")).joinToString("  ·  ")
            "chapter.read" -> "Read  ·  ${d.str("comic") ?: "a chapter"}"
            "habit.completed" -> listOfNotNull(d.str("tracker"), d.optDouble("value").takeIf { !it.isNaN() }?.let { Search.format(it) }, d.str("unit")).joinToString(" ")
                .ifBlank { "Logged" }
            "track.played" -> listOfNotNull("Played", d.str("title"), d.str("artist")).joinToString("  ·  ")
            "matchup.won" -> listOfNotNull("Won", d.str("league"), d.str("score")).joinToString("  ·  ")
            "booking.made" -> "Booked  ·  ${d.str("place") ?: "a trip"}"
            else -> d.str("title") ?: d.str("name") ?: d.str("tracker") ?: d.str("text") ?: e.type
        }
    }

    /**
     * Quests from 128bitlife still open: a `quest.issued` with no `quest.completed` for the same
     * quest (matched by data.quest, or else by title). Newest first.
     */
    fun openQuests(events: List<FamilyEvent>): List<FamilyEvent> {
        fun questOf(e: FamilyEvent) = e.data.str("quest") ?: e.data.str("title") ?: e.id
        val done = events.filter { it.type == "quest.completed" }.map(::questOf).toSet()
        return events.filter { it.type == "quest.issued" && questOf(it) !in done }.sortedByDescending { it.at }
    }

    /** Everything that isn't a quest, newest first. */
    fun lately(events: List<FamilyEvent>, max: Int = 5): List<FamilyEvent> =
        events.filter { !it.type.startsWith("quest.") }.sortedByDescending { it.at }.take(max)
}

/**
 * Signing in and talking to the family project. Every call runs off the main thread and reports
 * back on it; any failure comes back as one sentence for the screen.
 */
class FamilyAccount(private val context: Context) {
    private val store = SecureStore(context)
    private val sp = context.getSharedPreferences("familyInfo", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val email: String? get() = sp.getString("email", null)
    val signedIn: Boolean get() = email != null && store.get("refresh") != null
    var lastBackup: Long
        get() = sp.getLong("lastBackup", 0L)
        set(v) = sp.edit().putLong("lastBackup", v).apply()

    /** Why the last automatic backup failed, for Settings to say; null when it worked. */
    var backupProblem: String?
        get() = sp.getString("backupProblem", null)
        set(v) = sp.edit().putString("backupProblem", v).apply()

    class Failure(message: String) : Exception(message)

    private fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        worker.execute {
            val r = try {
                Result.success(work())
            } catch (e: Failure) {
                Result.failure(e)
            } catch (e: java.io.IOException) {
                Result.failure(Failure("No connection. Try again when you're online."))
            } catch (e: Exception) {
                Result.failure(Failure("Something went wrong talking to your 128bit account."))
            }
            main.post { done(r) }
        }
    }

    private fun http(path: String, body: JSONObject?, token: String? = null): String {
        val c = (URL(FamilyConfig.URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("apikey", FamilyConfig.KEY)
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code == 400 && path.contains("grant_type=password")) throw Failure("That email and password don't match a 128bit account.")
            if (code == 401 || code == 403) throw Failure("Your sign-in expired. Sign in again in Settings.")
            if (code !in 200..299) throw Failure("Your 128bit account said no (${code}). Try again later.")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun saveSession(json: String) {
        val o = JSONObject(json)
        store.put("access", o.getString("access_token"))
        store.put("refresh", o.getString("refresh_token"))
        sp.edit()
            .putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
            .putString("email", o.optJSONObject("user")?.optString("email") ?: email)
            .apply()
    }

    /** A token that works now, refreshing it if it's about to expire. Runs on the worker. */
    private fun token(): String {
        val access = store.get("access")
        if (access != null && System.currentTimeMillis() < sp.getLong("expires", 0) - 60_000) return access
        val refresh = store.get("refresh") ?: throw Failure("Sign in to your 128bit account first.")
        saveSession(http("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh)))
        return store.get("access")!!
    }

    fun signIn(email: String, password: String, done: (Result<Unit>) -> Unit) = run({
        saveSession(http("/auth/v1/token?grant_type=password", JSONObject().put("email", email.trim()).put("password", password)))
    }, done)

    /** Google: open the browser with a one-time challenge; [finishGoogle] completes it when it comes back. */
    fun googleUrl(): String {
        val verifier = Base64.encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        store.put("verifier", verifier)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "${FamilyConfig.URL}/auth/v1/authorize?provider=google&redirect_to=${android.net.Uri.encode(FamilyConfig.REDIRECT)}" +
            "&code_challenge=$challenge&code_challenge_method=s256"
    }

    fun finishGoogle(code: String, done: (Result<Unit>) -> Unit) = run({
        val verifier = store.get("verifier") ?: throw Failure("That sign-in took too long. Try again.")
        saveSession(http("/auth/v1/token?grant_type=pkce", JSONObject().put("auth_code", code).put("code_verifier", verifier)))
        store.put("verifier", null)
    }, done)

    fun signOut() {
        val access = store.get("access")
        if (access != null) worker.execute { try { http("/auth/v1/logout", JSONObject(), access) } catch (e: Exception) {} }
        store.put("access", null)
        store.put("refresh", null)
        sp.edit().clear().apply()
    }

    /** Save [backup] (layouts + settings) to the account, replacing the last one. */
    fun backUp(backup: JSONObject, done: (Result<Unit>) -> Unit) = run({
        http("/rest/v1/rpc/put_settings", JSONObject().put("new_data", backup).put("app_key", FamilyConfig.APP_KEY), token())
        lastBackup = System.currentTimeMillis()
    }, done)

    /** The backup kept in the account, or null if there isn't one yet. */
    fun fetchBackup(done: (Result<JSONObject?>) -> Unit) = run({
        val text = http("/rest/v1/rpc/get_settings", JSONObject().put("app_key", FamilyConfig.APP_KEY), token()).trim()
        if (text.isEmpty() || text == "null") null else JSONObject(text)
    }, done)

    /** The newest family-feed events. */
    fun events(max: Int, done: (Result<List<FamilyEvent>>) -> Unit) = run({
        FamilyFeed.parse(http("/rest/v1/rpc/get_events", JSONObject().put("max_rows", max), token()))
    }, done)
}
