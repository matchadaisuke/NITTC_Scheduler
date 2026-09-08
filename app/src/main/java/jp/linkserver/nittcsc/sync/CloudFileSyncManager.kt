package jp.linkserver.nittcsc.sync

import android.content.Context
import android.os.Build
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import jp.linkserver.nittcsc.BuildConfig
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.SchedulerRepository
import jp.linkserver.nittcsc.data.UiDesignPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone MEGA-backed sync.
 *
 * The user only signs in to MEGA. We store MEGA's session token (not the account password),
 * keep one JSON file under /NITTC Scheduler/, and synchronize that file against the local DB.
 * MEGA SDK classes are accessed through reflection so ordinary source builds still compile when
 * the SDK AAR has not been vendored yet; runtime setup reports a clear error in that case.
 */
object CloudFileSyncManager {
    private const val PREFS = "mega_sync"
    private const val KEY_SESSION = "session"
    private const val KEY_EMAIL = "email"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_REMOTE_UPDATED_AT = "last_remote_updated_at"
    private const val KEY_LOCAL_DIRTY = "local_dirty"
    private const val KEY_LOCAL_DIRTY_AT = "local_dirty_at"
    private const val KEY_LAST_STATUS = "last_status"

    private const val CLOUD_SCHEMA = "nittc-scheduler-cloud-sync"
    private const val CLOUD_VERSION = 2
    private const val REMOTE_FOLDER_NAME = "NITTC Scheduler"
    private const val REMOTE_FILE_NAME = "scheduler-sync.json"
    private const val PERIODIC_WORK = "mega_sync_periodic"
    private const val USER_AGENT = "NITTC-Scheduler/MEGA-Sync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val runtimeMutex = Mutex()
    private val applyingRemote = AtomicBoolean(false)
    private var databaseObserver: InvalidationTracker.Observer? = null
    private var pushJob: Job? = null

    @Volatile
    private var runtime: MegaRuntime? = null

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
        if (!isConfigured(appContext)) return
        schedulePeriodic(appContext)
        scope.launch {
            ensureRuntime(appContext)?.let {
                runCatching { syncNow(appContext) }
            }
        }
    }

    fun isConfigured(context: Context): Boolean =
        prefs(context).getString(KEY_SESSION, null).isNullOrBlank().not()

    fun linkedEmail(context: Context): String? =
        prefs(context).getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun sdkAvailable(): Boolean = runCatching {
        Class.forName("nz.mega.sdk.MegaApiAndroid", false, CloudFileSyncManager::class.java.classLoader)
        true
    }.getOrDefault(false)

    fun appKeyConfigured(): Boolean = BuildConfig.MEGA_APP_KEY.isNotBlank()

    fun lastStatus(context: Context): String =
        prefs(context).getString(KEY_LAST_STATUS, null)
            ?: if (isConfigured(context)) "MEGAに接続済み" else "未設定"

    /**
     * Log in without persisting the password. If [pin] is supplied, MEGA's MFA login endpoint
     * is used. A successful login persists only dumpSession() and the account email.
     */
    suspend fun login(
        context: Context,
        email: String,
        password: String,
        pin: String? = null
    ): String {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return setStatus(appContext, "MEGA同期はAndroid 9以降で利用できます")
        }
        if (!sdkAvailable()) {
            return setStatus(appContext, "MEGA SDKがアプリに組み込まれていません")
        }
        if (!appKeyConfigured()) {
            return setStatus(appContext, "MEGA App Keyがビルドに設定されていません")
        }
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            return setStatus(appContext, "MEGAのメールアドレスとパスワードを入力してください")
        }

        return runtimeMutex.withLock {
            val candidate = runCatching { MegaRuntime(appContext, BuildConfig.MEGA_APP_KEY) }
                .getOrElse {
                    return@withLock setStatus(appContext, "MEGA SDKの初期化に失敗しました: ${it.userMessage()}")
                }
            val result = runCatching {
                candidate.login(normalizedEmail, password, pin?.trim()?.takeIf { it.isNotEmpty() })
                candidate.fetchNodes()
                val session = candidate.dumpSession()
                require(session.isNotBlank()) { "MEGAセッションを取得できませんでした" }
                candidate.observeNodeChanges {
                    scope.launch {
                        delay(700)
                        runCatching { syncNow(appContext) }
                    }
                }
                runtime?.close()
                runtime = candidate
                prefs(appContext).edit()
                    .putString(KEY_SESSION, session)
                    .putString(KEY_EMAIL, normalizedEmail)
                    .putLong(KEY_LAST_REMOTE_UPDATED_AT, 0L)
                    .putBoolean(KEY_LOCAL_DIRTY, true)
                    .putLong(KEY_LOCAL_DIRTY_AT, System.currentTimeMillis())
                    .apply()
                schedulePeriodic(appContext)
                registerDatabaseObserver(appContext)
                syncNow(appContext, preferRemote = true)
            }
            result.getOrElse { error ->
                candidate.close()
                val message = when ((error as? MegaOperationException)?.code) {
                    -26 -> "2段階認証が有効です。認証コードを入力して再度ログインしてください"
                    -9 -> "MEGAのメールアドレスまたはパスワードが正しくありません"
                    -22 -> "MEGA App Keyが無効です"
                    else -> "MEGAへのログインに失敗しました: ${error.userMessage()}"
                }
                setStatus(appContext, message)
            }
        }
    }

    fun disconnect(context: Context) {
        val appContext = context.applicationContext
        val previousDeviceId = prefs(appContext).getString(KEY_DEVICE_ID, null)
        val active = runtime
        runtime = null
        scope.launch { runCatching { active?.logout() } }
        active?.close()
        prefs(appContext).edit().clear().apply()
        previousDeviceId?.let { prefs(appContext).edit().putString(KEY_DEVICE_ID, it).apply() }
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
            if (!isConfigured(appContext)) {
                return@withLock setStatus(appContext, "MEGA同期は未設定です")
            }
            val mega = ensureRuntime(appContext)
                ?: return@withLock lastStatus(appContext)
            val preferences = prefs(appContext)
            val localDeviceId = deviceId(appContext)

            val folder = runCatching { mega.ensureFolder(REMOTE_FOLDER_NAME) }.getOrElse {
                return@withLock setStatus(appContext, "MEGA同期フォルダを開けません: ${it.userMessage()}")
            }
            val remoteNode = runCatching { mega.getChild(folder, REMOTE_FILE_NAME) }.getOrNull()
            val remoteText = if (remoteNode != null) {
                runCatching { mega.downloadText(remoteNode) }.getOrElse {
                    return@withLock setStatus(appContext, "MEGAから同期ファイルを取得できません: ${it.userMessage()}")
                }
            } else {
                null
            }
            val remote = remoteText?.let(::parseRemote)
            if (!remoteText.isNullOrBlank() && remote == null) {
                return@withLock setStatus(appContext, "MEGA上の同期ファイル形式を読み取れません")
            }

            val dirty = preferences.getBoolean(KEY_LOCAL_DIRTY, false)
            val dirtyAt = preferences.getLong(KEY_LOCAL_DIRTY_AT, 0L)
            val lastRemote = preferences.getLong(KEY_LAST_REMOTE_UPDATED_AT, 0L)
            val shouldPull = remote != null && remote.payload.isNotBlank() && (
                preferRemote ||
                    (remote.updatedAt > lastRemote && remote.deviceId != localDeviceId &&
                        (!dirty || remote.updatedAt >= dirtyAt))
                )

            if (shouldPull) {
                val imported = runCatching {
                    applyingRemote.set(true)
                    repository(appContext).importAllData(remote!!.payload, requireSettings = true)
                    preferences.edit()
                        .putLong(KEY_LAST_REMOTE_UPDATED_AT, remote.updatedAt)
                        .putBoolean(KEY_LOCAL_DIRTY, false)
                        .putLong(KEY_LOCAL_DIRTY_AT, 0L)
                        .apply()
                }.also { applyingRemote.set(false) }
                if (imported.isFailure) {
                    return@withLock setStatus(appContext, "MEGA同期データの適用に失敗しました")
                }
                return@withLock setStatus(appContext, "MEGAから同期しました")
            }

            if (remote == null || dirty) {
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
                val uploaded = runCatching { mega.uploadText(folder, REMOTE_FILE_NAME, envelope) }
                if (uploaded.isFailure) {
                    return@withLock setStatus(appContext, "MEGAへのアップロードに失敗しました: ${uploaded.exceptionOrNull().userMessage()}")
                }
                preferences.edit()
                    .putLong(KEY_LAST_REMOTE_UPDATED_AT, updatedAt)
                    .putBoolean(KEY_LOCAL_DIRTY, false)
                    .putLong(KEY_LOCAL_DIRTY_AT, 0L)
                    .apply()
                return@withLock setStatus(appContext, "MEGAへ同期しました")
            }

            if (remote.updatedAt > lastRemote) {
                preferences.edit().putLong(KEY_LAST_REMOTE_UPDATED_AT, remote.updatedAt).apply()
            }
            setStatus(appContext, "MEGA同期済み")
        }
    }

    private suspend fun ensureRuntime(context: Context): MegaRuntime? {
        runtime?.let { return it }
        return runtimeMutex.withLock {
            runtime?.let { return@withLock it }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                setStatus(context, "MEGA同期はAndroid 9以降で利用できます")
                return@withLock null
            }
            if (!sdkAvailable()) {
                setStatus(context, "MEGA SDKがアプリに組み込まれていません")
                return@withLock null
            }
            if (!appKeyConfigured()) {
                setStatus(context, "MEGA App Keyがビルドに設定されていません")
                return@withLock null
            }
            val session = prefs(context).getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() }
                ?: return@withLock null
            val created = runCatching {
                MegaRuntime(context, BuildConfig.MEGA_APP_KEY).also { api ->
                    api.fastLogin(session)
                    api.fetchNodes()
                    api.observeNodeChanges {
                        scope.launch {
                            delay(700)
                            runCatching { syncNow(context) }
                        }
                    }
                }
            }.getOrElse { error ->
                setStatus(
                    context,
                    if ((error as? MegaOperationException)?.code == -15) {
                        "MEGAセッションの有効期限が切れました。再ログインしてください"
                    } else {
                        "MEGAセッションの復元に失敗しました: ${error.userMessage()}"
                    }
                )
                return@withLock null
            }
            runtime = created
            created
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
            runCatching { syncNow(context) }
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

    private fun parseRemote(text: String): RemoteSnapshot? = runCatching {
        val root = JSONObject(text)
        if (root.optString("schema") == CLOUD_SCHEMA) {
            RemoteSnapshot(
                updatedAt = root.optLong("updatedAt", 0L),
                deviceId = root.optString("deviceId", ""),
                payload = root.getJSONObject("payload").toString()
            )
        } else if (root.optString("schema") == "nittc-scheduler" || root.has("version")) {
            RemoteSnapshot(0L, "legacy", root.toString())
        } else {
            null
        }
    }.getOrNull()

    private fun repository(context: Context): SchedulerRepository = SchedulerRepository(
        AppDatabase.getInstance(context),
        UiDesignPreferences(context)
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceId(context: Context): String {
        val preferences = prefs(context)
        return preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    private fun setStatus(context: Context, value: String): String {
        prefs(context).edit().putString(KEY_LAST_STATUS, value).apply()
        return value
    }

    private data class RemoteSnapshot(
        val updatedAt: Long,
        val deviceId: String,
        val payload: String
    )
}

private class MegaRuntime(
    private val context: Context,
    appKey: String
) {
    private val apiClass = Class.forName("nz.mega.sdk.MegaApiAndroid")
    private val requestListenerClass = Class.forName("nz.mega.sdk.MegaRequestListenerInterface")
    private val transferListenerClass = Class.forName("nz.mega.sdk.MegaTransferListenerInterface")
    private val globalListenerClass = Class.forName("nz.mega.sdk.MegaGlobalListenerInterface")
    private val api: Any
    private var globalListener: Any? = null

    init {
        val cache = File(context.filesDir, "mega-sdk-cache").apply { mkdirs() }
        api = apiClass
            .getConstructor(String::class.java, String::class.java, String::class.java)
            .newInstance(appKey, "NITTC-Scheduler/MEGA-Sync", cache.absolutePath)
    }

    suspend fun login(email: String, password: String, pin: String?) {
        val result = if (pin.isNullOrBlank()) {
            request("login", email, password)
        } else {
            request("multiFactorAuthLogin", email, password, pin)
        }
        result.requireOk("ログイン")
    }

    suspend fun fastLogin(session: String) {
        request("fastLogin", session).requireOk("セッション復元")
    }

    suspend fun fetchNodes() {
        request("fetchNodes").requireOk("ファイル一覧取得")
    }

    fun dumpSession(): String =
        invokeApi("dumpSession") as? String ?: ""

    suspend fun ensureFolder(name: String): Any {
        val root = invokeApi("getRootNode")
            ?: throw IllegalStateException("MEGAルートフォルダを取得できません")
        getChild(root, name)?.let { return it }
        val result = request("createFolder", name, root).also { it.requireOk("同期フォルダ作成") }
        val handle = result.request?.let { invokeOn(it, "getNodeHandle") as? Number }?.toLong() ?: -1L
        if (handle != -1L) {
            invokeApi("getNodeByHandle", handle)?.let { return it }
        }
        return getChild(root, name)
            ?: throw IllegalStateException("作成したMEGA同期フォルダを取得できません")
    }

    fun getChild(parent: Any, name: String): Any? =
        invokeApi("getChildNode", parent, name)

    suspend fun uploadText(parent: Any, fileName: String, text: String) {
        val dir = File(context.cacheDir, "mega-sync-upload").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(text)
        try {
            transfer("startUpload", file.absolutePath, parent, fileName, -1L, null, false, false, null).requireOk("アップロード")
        } finally {
            runCatching { file.delete() }
        }
    }

    suspend fun downloadText(node: Any): String {
        val dir = File(context.cacheDir, "mega-sync-download-${UUID.randomUUID()}").apply { mkdirs() }
        val nodeName = (invokeOn(node, "getName") as? String).orEmpty().ifBlank { "scheduler-sync.json" }
        val destination = File(dir, nodeName)
        return try {
            transfer("startDownload", node, destination.absolutePath, null, null, false, null, 5, 1).requireOk("ダウンロード")
            destination.readText()
        } finally {
            runCatching { dir.deleteRecursively() }
        }
    }

    fun observeNodeChanges(onChanged: () -> Unit) {
        if (globalListener != null) return
        val listener = Proxy.newProxyInstance(
            globalListenerClass.classLoader,
            arrayOf(globalListenerClass)
        ) { proxy, method, args ->
            when (method.name) {
                "onNodesUpdate" -> onChanged()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "NittcMegaGlobalListener"
            }
            defaultReturn(method.returnType)
        }
        invokeApi("addGlobalListener", listener)
        globalListener = listener
    }

    suspend fun logout() {
        runCatching { request("logout").requireOk("ログアウト") }
    }

    fun close() {
        globalListener?.let { listener ->
            runCatching { invokeApi("removeGlobalListener", listener) }
        }
        globalListener = null
    }

    private suspend fun request(methodName: String, vararg args: Any?): RequestResult =
        withTimeout(60_000L) {
            val deferred = CompletableDeferred<RequestResult>()
            val listener = Proxy.newProxyInstance(
                requestListenerClass.classLoader,
                arrayOf(requestListenerClass)
            ) { proxy, method, callbackArgs ->
                when (method.name) {
                    "onRequestFinish" -> {
                        val request = callbackArgs?.getOrNull(1)
                        val error = callbackArgs?.getOrNull(2)
                        deferred.complete(
                            RequestResult(
                                code = error?.let { (invokeOn(it, "getErrorCode") as? Number)?.toInt() } ?: -1,
                                message = error?.let { invokeOn(it, "getErrorString") as? String },
                                request = request
                            )
                        )
                    }
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === callbackArgs?.getOrNull(0)
                    "toString" -> return@newProxyInstance "NittcMegaRequestListener"
                }
                defaultReturn(method.returnType)
            }
            invokeApi(methodName, *args, listener)
            deferred.await()
        }

    private suspend fun transfer(methodName: String, vararg args: Any?): TransferResult =
        withTimeout(120_000L) {
            val deferred = CompletableDeferred<TransferResult>()
            val listener = Proxy.newProxyInstance(
                transferListenerClass.classLoader,
                arrayOf(transferListenerClass)
            ) { proxy, method, callbackArgs ->
                when (method.name) {
                    "onTransferFinish" -> {
                        val error = callbackArgs?.getOrNull(2)
                        deferred.complete(
                            TransferResult(
                                code = error?.let { (invokeOn(it, "getErrorCode") as? Number)?.toInt() } ?: -1,
                                message = error?.let { invokeOn(it, "getErrorString") as? String }
                            )
                        )
                    }
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === callbackArgs?.getOrNull(0)
                    "toString" -> return@newProxyInstance "NittcMegaTransferListener"
                }
                defaultReturn(method.returnType)
            }
            invokeApi(methodName, *args, listener)
            deferred.await()
        }

    private fun invokeApi(name: String, vararg args: Any?): Any? =
        invokeCompatible(api, name, args)

    private fun invokeOn(target: Any, name: String, vararg args: Any?): Any? =
        invokeCompatible(target, name, args)

    private fun invokeCompatible(target: Any, name: String, args: Array<out Any?>): Any? {
        val methods = target.javaClass.methods.filter { it.name == name && it.parameterTypes.size == args.size }
        val method = methods.firstOrNull { candidate ->
            candidate.parameterTypes.zip(args).all { (parameter, argument) ->
                argument == null && !parameter.isPrimitive ||
                    argument != null && boxed(parameter).isAssignableFrom(argument.javaClass)
            }
        } ?: throw NoSuchMethodException(
            "${target.javaClass.name}.$name(${args.joinToString { it?.javaClass?.simpleName ?: "null" }})"
        )
        return method.invoke(target, *args)
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }

    private data class RequestResult(
        val code: Int,
        val message: String?,
        val request: Any?
    ) {
        fun requireOk(operation: String) {
            if (code != 0) throw MegaOperationException(code, "$operation: ${message ?: "MEGA error $code"}")
        }
    }

    private data class TransferResult(
        val code: Int,
        val message: String?
    ) {
        fun requireOk(operation: String) {
            if (code != 0) throw MegaOperationException(code, "$operation: ${message ?: "MEGA error $code"}")
        }
    }
}

private class MegaOperationException(
    val code: Int,
    message: String
) : IllegalStateException(message)

private fun defaultReturn(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> '\u0000'
    else -> null
}

private fun Throwable?.userMessage(): String {
    if (this == null) return "不明なエラー"
    val target = (this as? java.lang.reflect.InvocationTargetException)?.targetException ?: this
    return target.message?.takeIf { it.isNotBlank() } ?: target.javaClass.simpleName
}
