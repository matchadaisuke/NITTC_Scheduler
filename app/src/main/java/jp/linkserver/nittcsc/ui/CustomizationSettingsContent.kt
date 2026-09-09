package jp.linkserver.nittcsc.ui

import android.os.Build
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import jp.linkserver.nittcsc.R
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
    var email by remember { mutableStateOf(CloudFileSyncManager.linkedEmail(context).orEmpty()) }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val sdkAvailable = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && CloudFileSyncManager.sdkAvailable()
    }
    val appKeyConfigured = CloudFileSyncManager.appKeyConfigured()
    val notConfiguredText = stringResource(R.string.mega_sync_status_not_configured)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.sync_title_mega_sync), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.mega_sync_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> {
                    Text(stringResource(R.string.mega_sync_android_version_unsupported))
                }
                !sdkAvailable -> {
                    Text(stringResource(R.string.mega_sync_sdk_missing))
                }
                !appKeyConfigured -> {
                    Text(stringResource(R.string.mega_sync_app_key_missing))
                }
                !configured -> {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.mega_sync_email_label)) },
                        singleLine = true,
                        enabled = !busy
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.mega_sync_password_label)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.mega_sync_mfa_label)) },
                        singleLine = true,
                        enabled = !busy
                    )
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                status = CloudFileSyncManager.login(
                                    context = context,
                                    email = email,
                                    password = password,
                                    pin = pin.takeIf { it.isNotBlank() }
                                )
                                configured = CloudFileSyncManager.isConfigured(context)
                                if (configured) {
                                    email = CloudFileSyncManager.linkedEmail(context).orEmpty()
                                    password = ""
                                    pin = ""
                                }
                                busy = false
                            }
                        },
                        enabled = !busy && email.isNotBlank() && password.isNotBlank()
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.mega_sync_connecting
                                else R.string.mega_sync_login_button
                            )
                        )
                    }
                    Text(
                        stringResource(R.string.mega_sync_session_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text(
                        stringResource(
                            R.string.mega_sync_connected_account,
                            CloudFileSyncManager.linkedEmail(context).orEmpty()
                        )
                    )
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    status = CloudFileSyncManager.syncNow(context)
                                    busy = false
                                }
                            },
                            enabled = !busy
                        ) {
                            Text(
                                stringResource(
                                    if (busy) R.string.mega_sync_syncing
                                    else R.string.mega_sync_now_button
                                )
                            )
                        }
                        TextButton(
                            onClick = {
                                CloudFileSyncManager.disconnect(context)
                                configured = false
                                status = notConfiguredText
                            },
                            enabled = !busy
                        ) {
                            Text(stringResource(R.string.mega_sync_disconnect_button))
                        }
                    }
                    Text(
                        stringResource(R.string.mega_sync_runtime_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (status.isNotBlank() && !configured) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
