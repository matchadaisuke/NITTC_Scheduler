package jp.linkserver.nittcsc.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import jp.linkserver.nittcsc.data.LessonNotificationCustomization
import jp.linkserver.nittcsc.data.LessonNotificationTrigger
import jp.linkserver.nittcsc.data.SettingsEntity
import jp.linkserver.nittcsc.data.lessonNotificationCustomization
import jp.linkserver.nittcsc.data.schedulePresets
import jp.linkserver.nittcsc.sync.CloudFileSyncManager
import kotlinx.coroutines.launch

@Composable
internal fun CustomizationScheduleSettingsContent(
    settings: SettingsEntity?,
    onToggleSaturdayClasses: (Boolean) -> Unit,
    onSaveSchedulePreset: (String) -> Unit,
    onApplySchedulePreset: (String) -> Unit,
    onDeleteSchedulePreset: (String) -> Unit
) {
    settings ?: return
    val presets = settings.schedulePresets()
    var presetName by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("カスタム時間割", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("土曜授業")
                    Text(
                        "オンにすると土曜日の時間割を編集・表示・通知できます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.enableSaturdayClasses,
                    onCheckedChange = onToggleSaturdayClasses
                )
            }

            HorizontalDivider()
            Text("時程プリセット（最大5件）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("プリセット名") },
                placeholder = { Text("例: 通常時程 / 短縮時程") },
                singleLine = true
            )
            Button(
                onClick = {
                    onSaveSchedulePreset(presetName.trim())
                    presetName = ""
                },
                enabled = presetName.isNotBlank() && (presets.size < 5 || presets.any { it.name == presetName.trim() })
            ) {
                Text("現在の時程を保存")
            }

            presets.forEach { preset ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${preset.periodsPerDay}コマ / ${preset.firstPeriodStartHour.toString().padStart(2, '0')}:${preset.firstPeriodStartMinute.toString().padStart(2, '0')} 開始",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { onApplySchedulePreset(preset.id) }) {
                        Text("切替")
                    }
                    TextButton(onClick = { onDeleteSchedulePreset(preset.id) }) {
                        Text("削除")
                    }
                }
            }
        }
    }
}

@Composable
internal fun CustomizationNotificationSettingsContent(
    settings: SettingsEntity?,
    onUpdate: (LessonNotificationCustomization) -> Unit
) {
    settings ?: return
    val config = settings.lessonNotificationCustomization()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("通知タイミングと本文", style = MaterialTheme.typography.titleMedium)
            Text(
                "授業開始前2件・終了前2件を個別にオン/オフできます。開始前1は上の既存設定と連動します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            config.startTriggers.take(2).forEachIndexed { index, trigger ->
                NotificationTriggerEditor(
                    title = "授業開始前 ${index + 1}",
                    trigger = trigger,
                    onChange = { changed ->
                        val values = config.startTriggers.toMutableList().also { it[index] = changed }
                        onUpdate(config.copy(startTriggers = values))
                    }
                )
            }
            config.endTriggers.take(2).forEachIndexed { index, trigger ->
                NotificationTriggerEditor(
                    title = "授業終了前 ${index + 1}",
                    trigger = trigger,
                    onChange = { changed ->
                        val values = config.endTriggers.toMutableList().also { it[index] = changed }
                        onUpdate(config.copy(endTriggers = values))
                    }
                )
            }

            HorizontalDivider()
            Text(
                "使用可能な変数: {subject} {teacher} {location} {period} {start_time} {end_time} {minutes} {next_subject} {next_teacher} {next_location} {next_period} {next_start_time}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TemplateField(
                label = "開始通知タイトル",
                value = config.startTitleTemplate,
                onChange = { onUpdate(config.copy(startTitleTemplate = it)) }
            )
            TemplateField(
                label = "開始通知本文",
                value = config.startBodyTemplate,
                multiline = true,
                onChange = { onUpdate(config.copy(startBodyTemplate = it)) }
            )
            TemplateField(
                label = "終了通知タイトル",
                value = config.endTitleTemplate,
                onChange = { onUpdate(config.copy(endTitleTemplate = it)) }
            )
            TemplateField(
                label = "終了通知本文",
                value = config.endBodyTemplate,
                multiline = true,
                onChange = { onUpdate(config.copy(endBodyTemplate = it)) }
            )
        }
    }
}

@Composable
private fun NotificationTriggerEditor(
    title: String,
    trigger: LessonNotificationTrigger,
    onChange: (LessonNotificationTrigger) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Switch(
            checked = trigger.enabled,
            onCheckedChange = { onChange(trigger.copy(enabled = it)) }
        )
        Text(title, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = trigger.minutesBefore.toString(),
            onValueChange = { raw ->
                raw.filter(Char::isDigit).toIntOrNull()?.let { minutes ->
                    onChange(trigger.copy(minutesBefore = minutes.coerceIn(0, 360)))
                }
            },
            modifier = Modifier.weight(0.75f),
            label = { Text("分前") },
            singleLine = true,
            enabled = trigger.enabled
        )
    }
}

@Composable
private fun TemplateField(
    label: String,
    value: String,
    multiline: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1
    )
}

@Composable
internal fun CloudFileSyncSettingsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(CloudFileSyncManager.lastStatus(context)) }
    var configured by remember { mutableStateOf(CloudFileSyncManager.isConfigured(context)) }

    fun connect(uri: Uri?, preferRemote: Boolean) {
        uri ?: return
        scope.launch {
            status = CloudFileSyncManager.configure(context, uri, preferRemote)
            configured = true
        }
    }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> connect(uri, false) }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> connect(uri, true) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("クラウドファイル同期", style = MaterialTheme.typography.titleMedium)
            Text(
                "Androidのファイル選択を使うため、Google Drive / Dropbox / OneDriveなどを追加API設定なしで利用できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { createLauncher.launch("NITTC_Scheduler_sync.json") }) {
                    Text("新規同期ファイル")
                }
                OutlinedButton(onClick = { openLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                    Text("既存ファイルに接続")
                }
            }
            if (configured) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch { status = CloudFileSyncManager.syncNow(context) }
                    }) {
                        Text("今すぐ同期")
                    }
                    TextButton(onClick = {
                        CloudFileSyncManager.disconnect(context)
                        configured = false
                        status = "未設定"
                    }) {
                        Text("解除")
                    }
                }
            }
            Text(
                "アプリ実行中は変更を検知して自動同期し、バックグラウンドでは約15分間隔のフォールバック同期を行います。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
