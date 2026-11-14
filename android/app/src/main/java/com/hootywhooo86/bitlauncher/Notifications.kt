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
    /** Can be swiped away (not ongoing, not "no clear"). */
    val clearable: Boolean = true,
    /** Goes away by itself once opened. */
    val autoCancel: Boolean = false,
    /** Important enough to pop up (default importance or higher, not silent). */
    val loud: Boolean = true,
)

/** One notification of an opened-up app on the lock screen, with only what the privacy rules allow. */
data class LockNote(val key: String, val title: String?, val text: String?, val intent: PendingIntent?, val clearable: Boolean, val autoCancel: Boolean, val posted: Long)

/** One row on the lock screen: an app, how many, and whatever text the privacy rules allow. */
data class LockLine(val pkg: String, val user: Long, val count: Int, val title: String?, val text: String?, val intent: PendingIntent?, val posted: Long = 0L)

object NotePrivacy {
    const val APPS_ONLY = 0
    const val TITLES = 1
    const val FULL = 2

    /** Notifications worth a dot or a lock-screen row: not ongoing (music, downloads), not group headers. */
    fun countable(n: Note) = !n.ongoing && !n.groupSummary

    /**
     * Rows for the lock screen, newest app first, one row per app.
     * - SECRET notifications never show, not even the app.
     * - PRIVATE ones show in full, like on Android's own lock screen, since reaching here means
     *   the phone allows sensitive content on the lock screen (see below).
     * - [detail] is the most the user allows; [systemHidesSensitive] (the phone's own
     *   "hide sensitive content") caps everything at app names.
     */
    fun lockLines(notes: List<Note>, detail: Int, systemHidesSensitive: Boolean): List<LockLine> {
        val level = if (systemHidesSensitive) APPS_ONLY else detail
        val shown = notes.filter(::lockVisible)
        return shown.groupBy { it.pkg to it.user }
            .map { (app, list) ->
                val newest = list.maxBy { it.posted }
                val (title, text) = words(newest, level)
                LockLine(app.first, app.second, list.size, title, text, newest.intent, newest.posted) to newest.posted
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun lockVisible(n: Note) = countable(n) && n.visibility != Notification.VISIBILITY_SECRET

    /** The title and text one notification may show at [level]. */
    private fun words(n: Note, level: Int): Pair<String?, String?> = when (level) {
        APPS_ONLY -> null to null
        else -> n.title.takeIf { it.isNotBlank() } to n.text.takeIf { level == FULL && it.isNotBlank() }
    }

    /** Every notification from one app, newest first, by the same rules as [lockLines]. */
    fun lockNotes(notes: List<Note>, pkg: String, user: Long, detail: Int, systemHidesSensitive: Boolean): List<LockNote> {
        val level = if (systemHidesSensitive) APPS_ONLY else detail
        return notes.filter { it.pkg == pkg && it.user == user && lockVisible(it) }
            .sortedByDescending { it.posted }
            .map { n ->
                val (title, text) = words(n, level)
                LockNote(n.key, title, text, n.intent, n.clearable, n.autoCancel, n.posted)
            }
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

    private val arrivals = mutableSetOf<(Note) -> Unit>()

    /** Told about each notification worth popping up, as it arrives (not updates of one already here). */
    fun onArrival(l: (Note) -> Unit) { arrivals.add(l) }
    fun offArrival(l: (Note) -> Unit) { arrivals.remove(l) }

    /** Should [new] pop up? Only fresh, loud, countable ones: no progress-bar updates, no silent ones. */
    fun worthPopping(previous: Note?, new: Note): Boolean =
        NotePrivacy.countable(new) && new.loud && (previous == null || new.posted > previous.posted + 1000) &&
            (new.title.isNotBlank() || new.text.isNotBlank())

    /** Swipe a notification away (for real: it leaves the phone's shade too). */
    fun dismiss(key: String) = BitNotificationService.running?.dismiss(key)

    /** Clear everything that can be cleared. */
    fun dismissAll() = BitNotificationService.running?.dismissAll()

    internal fun replaceAll(list: List<Note>) {
        synchronized(notes) {
            notes.clear()
            list.forEach { notes[it.key] = it }
        }
        changed()
    }

    internal fun put(n: Note) {
        val previous = synchronized(notes) { notes.put(n.key, n) }
        changed()
        if (worthPopping(previous, n)) main.post { arrivals.toList().forEach { it(n) } }
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
    companion object {
        /** The connected service, so the panel can dismiss notifications. */
        @Volatile
        var running: BitNotificationService? = null
            private set
    }

    fun dismiss(key: String) = try {
        cancelNotification(key)
    } catch (e: Exception) {
    }

    fun dismissAll() = try {
        cancelAllNotifications()
    } catch (e: Exception) {
    }

    private val popups by lazy { OverlayPopups(this) }
    private val onArrival: (Note) -> Unit = { popups.maybeShow(it) }

    override fun onListenerConnected() {
        running = this
        NotificationHub.onArrival(onArrival)
        NotificationHub.setConnected(true)
        val all = try {
            activeNotifications?.mapNotNull(::toNote) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        NotificationHub.replaceAll(all)
    }

    override fun onListenerDisconnected() {
        running = null
        NotificationHub.offArrival(onArrival)
        NotificationHub.setConnected(false)
    }

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
            clearable = sbn.isClearable,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            loud = try {
                val ranking = Ranking()
                !currentRanking.getRanking(sbn.key, ranking) ||
                    (ranking.importance >= android.app.NotificationManager.IMPORTANCE_DEFAULT && ranking.matchesInterruptionFilter())
            } catch (e: Exception) {
                true
            },
        )
    }
}

/**
 * Open what a notification points at. Android 14 and later block an app from opening a screen
 * for a notification unless whoever sends it vouches for it, so this does: without it the tap
 * goes nowhere, with no error. Throws if the notification is gone.
 */
fun openNotification(pi: PendingIntent) {
    val options = if (android.os.Build.VERSION.SDK_INT >= 34) {
        android.app.ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    } else null
    pi.send(null, 0, null, null, null, null, options)
}

/**
 * What to do once the phone is unlocked (open the tapped notification). The unlock screen runs it
 * the moment the PIN is accepted, while it's still on screen and so allowed to open things.
 */
object PendingOpen {
    @Volatile
    private var action: (() -> Unit)? = null

    fun set(a: (() -> Unit)?) { action = a }

    /** Run it, once. */
    fun run() {
        val a = synchronized(this) { action.also { action = null } }
        a?.invoke()
    }

    val waiting get() = action != null
}
