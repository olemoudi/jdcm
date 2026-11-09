package dev.jdcm

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.jdcm.data.EcmApi
import dev.jdcm.data.GroupCache
import dev.jdcm.data.KidStore
import dev.jdcm.data.Repository
import dev.jdcm.data.Settings
import dev.jdcm.data.SyncMode
import dev.jdcm.diag.DiagLog
import dev.jdcm.notify.Notifier
import dev.jdcm.notify.Reminders
import dev.jdcm.update.SharedPrefsUpdatePrefs
import dev.jdcm.update.UpdateChannel
import dev.jdcm.update.Updates
import dev.jdcm.update.PublicRepoSource
import dev.jdcm.work.SyncWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI: one of each, created once per process. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> DiagLog.e("App", "uncaught in scope", e) })
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    val settings = Settings(app)
    val kids = KidStore(app)
    val groups = GroupCache(app)
    val api = EcmApi(http)
    val notifier = Notifier(app, settings)
    val repository = Repository(scope, api, groups, kids, onChanges = { notifier.changes(it) }, onFetched = { Reminders.rearm(app) })
}

class JdcmApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this) // everything after this is caught, crashes included
        DiagLog.i("App", "process start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_SHA} api ${Build.VERSION.SDK_INT}")
        container = AppContainer(this)
        container.notifier.ensureChannels()

        // The build's own channel by default: an alpha APK installed by hand follows alpha, the
        // install link's beta follows beta. Settings → Updates can switch either way.
        val defaultChannel = if (BuildConfig.VERSION_NAME.endsWith(UpdateChannel.ALPHA.suffix)) UpdateChannel.ALPHA else UpdateChannel.BETA
        Updates.init(this, SharedPrefsUpdatePrefs(this, defaultChannel), PublicRepoSource(container.http), container.http)

        // Re-enqueued only when the build changed: a background start (the worker itself) costs no
        // WorkManager write. Alarms are re-armed when the app is shown, on boot and after an update.
        if (container.settings.scheduledForVersion != BuildConfig.VERSION_CODE) {
            SyncWorker.schedule(this)
            container.settings.scheduledForVersion = BuildConfig.VERSION_CODE
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                DiagLog.i("App", "shown")
                // Opening the app: refresh the children's groups if stale (cached data shows meanwhile).
                Reminders.rearm(this@JdcmApp)
                container.scope.launch {
                    container.repository.syncKids(SyncMode.IF_STALE, "shown")
                    // Groups looked at once (explorer, team search) and untouched for a month go.
                    val keep = container.kids.current().map { it.groupId }.toSet()
                    container.groups.prune(keep, System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
                }
            }
            override fun onStop(owner: LifecycleOwner) = DiagLog.i("App", "hidden")
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = {
                container.http.newBuilder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", EcmApi.USER_AGENT).build())
                }.build()
            }))
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("crests")).maxSizeBytes(40L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}

val Context.container: AppContainer get() = (applicationContext as JdcmApp).container
