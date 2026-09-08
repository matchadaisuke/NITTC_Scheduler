package jp.linkserver.nittcsc.sync

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.SchedulerRepository
import jp.linkserver.nittcsc.data.UiDesignPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object CloudFileSyncManager {
    private const val PREFS = "cloud_file_sync"
    private const val KEY_URI = "uri"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_REMOTE_UPDATED_AT = "last_remote_updated_at"
    private const val KEY_LOCAL_DIRTY = "local_dirty"
    private const val KEY_LOCAL_DIRTY_AT = "local_dirty_at"
    private const val KEY_LAST_STATUS = "last_status"
    private const val CLOUD_SCHEMA = "nittc-scheduler-cloud-sync"
    private const val CLOUD_VERSION = 1
    private const val PERIODIC_WORK = "cloud_file_sync_periodic"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val applyingRemote = AtomicBoolean(false)
    private val writingRemote = AtomicBoolean(false)
    private var databaseObserver: InvalidationTracker.Observer? = null
    private var contentObserver: ContentObserver? = null
    private var pushJob: Job? = null

    private val watchedTables = arrayOf(
        "settings",
        "day_types",
        "long_breaks",
        "lessons",
        "cancelled_lessons",
        "changed_lessons",
        "lesson_notes",
        "exam_day_schedules",
        "exam_lessons",
        "lesson_notification_exclusions",
        "tasks",
        "plans"
    )

    fun start(context: Context) {
        val appContext = context.applicationContext
        registerDatabaseObserver(appContext)
        registerContentObserver(appContext)
        if (isConfigured(appContext)) schedulePeriodic(appContext)
    }

    fun isConfigured(context: Context): Boolean = configuredUri(context) != null

    fun configuredUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null)
            ?.let(Uri::parse)

    fun lastStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STATUS, null)
            ?: if (isConfigured(context)) "同期ファイルに接続済み" else "未設定"

    suspend fun configure(context: Context, uri: Uri, preferRemote: Boolean): String {
        val appContext = context.applicationContext
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val prefs = prefs(appContext)
        prefs.edit()
            .putString(KEY_URI, uri.toString())
            .putLong(KEY_LAST_REMOTE_UPDATED_AT, 0L)
            .putBoolean(KEY_LOCAL_DIRTY, !preferRemote)
            .putLong(KEY_LOCAL_DIRTY_AT, if (preferRemote) 0L else System.currentTimeMillis())
            .apply()
        registerContentObserver(appContext)
        registerDatabaseObserver(appContext)
        schedulePeriodic(appContext)
        return syncNow(appContext, preferRemote = preferRemote)
    }

    fun disconnect(context: Context) {
        val appContext = context.applicationContext
        configuredUri(appContext)?.let { uri ->
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        contentObserver?.let { runCatching { appContext.contentResolver.unregisterContentObserver(it) } }
        contentObserver = null
        prefs(appContext).edit().clear().apply()
        WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_WORK)
    }

    fun requestImmediateSync(context: Context) {
        val appContext = context.applicationContext
        markLocalDirty(appContext)
        scheduleDebouncedPush(appContext)
    }

    suspend fun syncNow(context: Context, preferRemote: Boolean = false): String {
        val appContext = context.applicationContext
        return syncMutex.withLock {
            val uri = configuredUri(appContext) ?: return@withLock setStatus(appContext, "クラウド同期は未設定です")
            val prefs = prefs(appContext)
            val localDeviceId = deviceId(appContext)
            val remote = readRemote(appContext, uri)
            val dirty = prefs.getBoolean(KEY_LOCAL_DIRTY, false)
            val dirtyAt = prefs.getLong(KEY_LOCAL_DIRTY_AT, 0L)
            val lastRemote = prefs.getLong(KEY_LAST_REMOTE_UPDATED_AT, 0L)

            val shouldPull = remote != null && remote.payload.isNotBlank() && (
                preferRemote ||
                    (remote.updatedAt > lastRemote && remote.deviceId != localDeviceId && (!dirty || remote.updatedAt >= dirtyAt))
                )

            if (shouldPull) {
                val result = runCatching {
                    applyingRemote.set(true)
                    repository(appContext).importAllData(remote!!.payload, requireSettings = true)
                    prefs.edit()
                        .putLong(KEY_LAST_REMOTE_UPDATED_AT, remote.updatedAt)
                        .putBoolean(KEY_LOCAL_DIRTY, false)
                        .putLong(KEY_LOCAL_DIRTY_AT, 0L)
                        .apply()
                }.also { applyingRemote.set(false) }
                if (result.isFailure) {
                    return@withLock setStatus(appContext, "同期ファイルの読み込みに失敗しました")
                }
                return@withLock setStatus(appContext, "クラウドから同期しました")
            }

            if (remote == null || dirty || preferRemote) {
                val payload = runCatching { repository(appContext).exportAllData() }.getOrElse {
                    return@withLock setStatus(appContext, "同期データの作成に失敗しました")
                }
                val updatedAt = maxOf(System.currentTimeMillis(), (remote?.updatedAt ?: 0L) + 1L)
                val envelope = JSONObject().apply {
                    put("schema", CLOUD_SCHEMA)
                    put("version", CLOUD_VERSION)
                    put("updatedAt", updatedAt)
                    put("deviceId", localDeviceId)
                    put("payload", JSONObject(payload))
                }.toString(2)
                val written = writeRemote(appContext, uri, envelope)
                if (!written) return@withLock setStatus(appContext, "同期ファイルへの書き込みに失敗しました")
                prefs.edit()
                    .putLong(KEY_LAST_REMOTE_UPDATED_AT, updatedAt)
                    .putBoolean(KEY_LOCAL_DIRTY, false)
                    .putLong(KEY_LOCAL_DIRTY_AT, 0L)
                    .apply()
                return@withLock setStatus(appContext, "クラウドへ同期しました")
            }

            if (remote.updatedAt > lastRemote) {
                prefs.edit().putLong(KEY_LAST_REMOTE_UPDATED_AT, remote.updatedAt).apply()
            }
            setStatus(appContext, "同期済み")
        }
    }

    private fun registerDatabaseObserver(context: Context) {
        if (databaseObserver != null) return
        val db = AppDatabase.getInstance(context)
        val observer = object : InvalidationTracker.Observer(watchedTables) {
            override fun onInvalidated(tables: Set<String>) {
                if (applyingRemote.get()) return
                markLocalDirty(context)
                scheduleDebouncedPush(context)
            }
        }
        db.invalidationTracker.addObserver(observer)
        databaseObserver = observer
    }

    private fun registerContentObserver(context: Context) {
        val uri = configuredUri(context) ?: return
        contentObserver?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (writingRemote.get()) return
                scope.launch {
                    delay(500)
                    syncNow(context)
                }
            }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        contentObserver = observer
    }

    private fun markLocalDirty(context: Context) {
        if (!isConfigured(context)) return
        prefs(context).edit()
            .putBoolean(KEY_LOCAL_DIRTY, true)
            .putLong(KEY_LOCAL_DIRTY_AT, System.currentTimeMillis())
            .apply()
    }

    private fun scheduleDebouncedPush(context: Context) {
        if (!isConfigured(context)) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(900)
            syncNow(context)
        }
    }

    private fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudFileSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun repository(context: Context): SchedulerRepository = SchedulerRepository(
        AppDatabase.getInstance(context),
        UiDesignPreferences(context)
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceId(context: Context): String {
        val preferences = prefs(context)
        return preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    private data class RemoteSnapshot(
        val updatedAt: Long,
        val deviceId: String,
        val payload: String
    )

    private fun readRemote(context: Context, uri: Uri): RemoteSnapshot? {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.trim().orEmpty()
        if (text.isBlank()) return null
        return runCatching {
            val root = JSONObject(text)
            if (root.optString("schema") == CLOUD_SCHEMA) {
                RemoteSnapshot(
                    updatedAt = root.optLong("updatedAt", 0L),
                    deviceId = root.optString("deviceId", ""),
                    payload = root.getJSONObject("payload").toString()
                )
            } else if (root.optString("schema") == "nittc-scheduler" || root.has("version")) {
                RemoteSnapshot(
                    updatedAt = 0L,
                    deviceId = "legacy",
                    payload = root.toString()
                )
            } else {
                null
            }
        }.getOrNull()
    }

    private fun writeRemote(context: Context, uri: Uri, text: String): Boolean {
        writingRemote.set(true)
        return try {
            runCatching {
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: context.contentResolver.openOutputStream(uri, "w")
                    ?: return false
                stream.bufferedWriter().use { it.write(text) }
            }.isSuccess
        } finally {
            writingRemote.set(false)
        }
    }

    private fun setStatus(context: Context, value: String): String {
        prefs(context).edit().putString(KEY_LAST_STATUS, value).apply()
        return value
    }
}
