package jp.linkserver.nittcsc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.SchedulerRepository
import jp.linkserver.nittcsc.data.UiDesignPreferences
import jp.linkserver.nittcsc.reminder.LessonStartNotificationWorker
import jp.linkserver.nittcsc.reminder.PlanReminderWorker
import jp.linkserver.nittcsc.reminder.TaskReminderWorker
import jp.linkserver.nittcsc.sync.CloudFileSyncManager
import jp.linkserver.nittcsc.sync.LocalSyncManager
import jp.linkserver.nittcsc.sync.NearbySyncManager
import jp.linkserver.nittcsc.ui.NittcSchedulerApp
import jp.linkserver.nittcsc.ui.theme.AppTheme
import jp.linkserver.nittcsc.viewmodel.SchedulerViewModel
import jp.linkserver.nittcsc.viewmodel.SchedulerViewModelFactory
import jp.linkserver.nittcsc.widget.WidgetUpdateWorker
import jp.linkserver.nittcsc.widget.WidgetUpdater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: SchedulerViewModel by viewModels {
        val database = AppDatabase.getInstance(this)
        val repository = SchedulerRepository(database, UiDesignPreferences(this))
        val syncManager = LocalSyncManager(this, repository, database)
        val nearbySyncManager = NearbySyncManager(this, repository)
        SchedulerViewModelFactory(repository, syncManager, nearbySyncManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        enableEdgeToEdge()
        setContent {
            val uiDesignMode by viewModel.uiDesignMode.collectAsStateWithLifecycle()
            AppTheme(uiDesignMode = uiDesignMode) {
                NittcSchedulerApp(viewModel = viewModel)
            }
        }
        CloudFileSyncManager.start(this)
        WidgetUpdateWorker.schedule(this)
        lifecycleScope.launch {
            TaskReminderWorker.rescheduleAll(this@MainActivity)
            PlanReminderWorker.rescheduleAll(this@MainActivity)
            LessonStartNotificationWorker.rescheduleAll(this@MainActivity)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            viewModel.refreshAcademicYear()
            WidgetUpdater.updateTaskWidgets(this@MainActivity)
            WidgetUpdater.updateAll(this@MainActivity)
            viewModel.runAutoSync()
            if (CloudFileSyncManager.isConfigured(this@MainActivity)) {
                CloudFileSyncManager.syncNow(this@MainActivity)
            }
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }
}
