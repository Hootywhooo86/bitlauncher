package com.hootywhooo86.bitlauncher

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** What we keep about one notification. Plain data so the privacy rules can be tested. */
data class Note(
    val key: String,
    val pkg: String,
    val user: Long,
    val title: String,
    val text: String,
    /** Notification.VISIBILITY_PUBLIC / PRIVATE / SECRET. */
    val visibility: Int,
    val publicTitle: String?,
    val publicText: String?,
    val ongoing: Boolean,
    val groupSummary: Boolean,
    val posted: Long,
    val intent: PendingIntent? = null,
)

/** One row on the lock screen: an app, how many, and whatever text the privacy rules allow. */
data class LockLine(val pkg: String, val user: Long, val count: Int, val title: String?, val text: String?, val intent: PendingIntent?)

object NotePrivacy {
    const val APPS_ONLY = 0
    const val TITLES = 1
    const val FULL = 2

    /** Notifications worth a dot or a lock-screen row: not ongoing (music, downloads), not group headers. */
    fun countable(n: Note) = !n.ongoing && !n.groupSummary

    /**
     * Rows for the lock screen, newest app first, one row per app.
     * - SECRET notifications never show, not even the app.
     * - PRIVATE ones show their public version if the app gave one, otherwise only the app.
     * - [detail] is the most the user allows; [systemHidesSensitive] (the phone's own
     *   "hide sensitive content") caps everything at app names.
     */
    fun lockLines(notes: List<Note>, detail: Int, systemHidesSensitive: Boolean): List<LockLine> {
        val level = if (systemHidesSensitive) APPS_ONLY else detail
        val shown = notes.filter { countable(it) && it.visibility != Notification.VISIBILITY_SECRET }
        return shown.groupBy { it.pkg to it.user }
            .map { (app, list) ->
                val newest = list.maxBy { it.posted }
                val (title, text) = when {
                    level == APPS_ONLY -> null to null
                    newest.visibility == Notification.VISIBILITY_PRIVATE ->
                        newest.publicTitle?.takeIf { it.isNotBlank() } to newest.publicText?.takeIf { level == FULL && it.isNotBlank() }
                    else -> newest.title.takeIf { it.isNotBlank() } to newest.text.takeIf { level == FULL && it.isNotBlank() }
                }
                LockLine(app.first, app.second, list.size, title, text, newest.intent) to newest.posted
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}

/**
 * The phone's notifications, as far as bitlauncher needs them: which apps have something new
 * (for dots) and what the lock screen may show. Lives in memory only; nothing is stored.
 */
object NotificationHub {
    private val notes = LinkedHashMap<String, Note>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** True while the listener service is connected. */
    var connected = false
        private set

    fun snapshot(): List<Note> = synchronized(notes) { notes.values.toList() }

    /** "pkg/user" for every app with at least one countable notification. */
    fun appsWithDots(): Set<String> = snapshot().filter(NotePrivacy::countable).map { "${it.pkg}/${it.user}" }.toSet()

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    internal fun replaceAll(list: List<Note>) {
        synchronized(notes) {
            notes.clear()
            list.forEach { notes[it.key] = it }
        }
        changed()
    }

    internal fun put(n: Note) {
        synchronized(notes) { notes[n.key] = n }
        changed()
    }

    internal fun remove(key: String) {
        synchronized(notes) { notes.remove(key) }
        changed()
    }

    internal fun setConnected(v: Boolean) {
        connected = v
        if (!v) synchronized(notes) { notes.clear() }
        changed()
    }

    private fun changed() = main.post { listeners.toList().forEach { it() } }

    /** Has the user granted bitlauncher notification access? */
    fun hasAccess(context: Context): Boolean {
        val me = ComponentName(context, BitNotificationService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { it == me }
    }

    /**
     * The phone's "hide sensitive notification content on the lock screen". If we can't read it,
     * assume it's on: showing less is the safe mistake.
     */
    fun systemHidesSensitive(context: Context): Boolean = try {
        Settings.Secure.getInt(context.contentResolver, "lock_screen_allow_private_notifications", 0) == 0
    } catch (e: Exception) {
        true
    }
}

/** Reads notifications for dots and the lock screen. Android asks the user before this can run. */
class BitNotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationHub.setConnected(true)
        val all = try {
            activeNotifications?.mapNotNull(::toNote) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        NotificationHub.replaceAll(all)
    }

    override fun onListenerDisconnected() = NotificationHub.setConnected(false)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        toNote(sbn)?.let(NotificationHub::put)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotificationHub.remove(sbn.key)

    private fun toNote(sbn: StatusBarNotification): Note? {
        val n = sbn.notification ?: return null
        val extras = n.extras
        val pub = n.publicVersion?.extras
        val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(sbn.user)
        return Note(
            key = sbn.key,
            pkg = sbn.packageName,
            user = serial,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            visibility = n.visibility,
            publicTitle = pub?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            publicText = pub?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            ongoing = sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            posted = sbn.postTime,
            intent = n.contentIntent,
        )
    }
}
