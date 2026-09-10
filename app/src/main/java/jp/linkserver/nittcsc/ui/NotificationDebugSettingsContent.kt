package jp.linkserver.nittcsc.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.linkserver.nittcsc.R
import jp.linkserver.nittcsc.reminder.ReminderDebugTestController
import jp.linkserver.nittcsc.reminder.ReminderDebugTestSnapshot
import jp.linkserver.nittcsc.reminder.ReminderDebugEnvironment
import jp.linkserver.nittcsc.ui.components.AppSettingsCategory
import jp.linkserver.nittcsc.ui.components.AppSettingsGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun NotificationDebugSettingsContent() {
    val context = LocalContext.current
    var alarmDelaySeconds by rememberSaveable { mutableStateOf("10") }
    var testSnapshot by remember {
        mutableStateOf(ReminderDebugTestController.readTestSnapshot(context))
    }
    var environment by remember {
        mutableStateOf(ReminderDebugTestController.readEnvironment(context))
    }
    val immediateSuccessMessage = stringResource(R.string.reminder_debug_immediate_success)
    val immediateFailureMessage = stringResource(R.string.reminder_debug_notification_failure)
    val alarmSuccessMessage = stringResource(R.string.reminder_debug_alarm_success)
    val alarmFailureMessage = stringResource(R.string.reminder_debug_alarm_failure)

    LaunchedEffect(context) {
        while (isActive) {
            testSnapshot = ReminderDebugTestController.readTestSnapshot(context)
            environment = ReminderDebugTestController.readEnvironment(context)
            delay(500L)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppSettingsCategory(title = stringResource(R.string.reminder_debug_section_title))
        AppSettingsGroup {
            item("reminder_debug_controls", contentPadding = PaddingValues(20.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.reminder_debug_description))
                    ReminderDebugEnvironmentStatus(environment)
                    OutlinedButton(
                        onClick = {
                            val success = ReminderDebugTestController.sendImmediateNotification(context)
                            Toast.makeText(
                                context,
                                if (success) immediateSuccessMessage else immediateFailureMessage,
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.reminder_debug_notification_test))
                    }
                    OutlinedTextField(
                        value = alarmDelaySeconds,
                        onValueChange = { value ->
                            if (value.all(Char::isDigit) && value.length <= 3) alarmDelaySeconds = value
                        },
                        label = { Text(stringResource(R.string.reminder_debug_alarm_delay)) },
                        supportingText = { Text(stringResource(R.string.reminder_debug_alarm_delay_description)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            val delay = alarmDelaySeconds.toIntOrNull()?.coerceIn(1, 300) ?: 10
                            alarmDelaySeconds = delay.toString()
                            val success = ReminderDebugTestController.scheduleAlarmTest(context, delay)
                            Toast.makeText(
                                context,
                                if (success) alarmSuccessMessage else alarmFailureMessage,
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.reminder_debug_alarm_test))
                    }
                    if (!testSnapshot.isEmpty) {
                        ReminderDebugTestResult(testSnapshot)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderDebugEnvironmentStatus(environment: ReminderDebugEnvironment) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.reminder_debug_environment_title),
            style = MaterialTheme.typography.titleSmall
        )
        ReminderDebugStageRow(
            R.string.reminder_debug_environment_app_notifications,
            environment.notificationsAllowed
        )
        ReminderDebugStageRow(
            R.string.reminder_debug_environment_task_channel,
            environment.taskChannelEnabled
        )
        ReminderDebugStageRow(
            R.string.reminder_debug_environment_exact_alarm,
            environment.exactAlarmAvailable
        )
    }
}

@Composable
private fun ReminderDebugTestResult(snapshot: ReminderDebugTestSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.reminder_debug_result_title),
            style = MaterialTheme.typography.titleSmall
        )
        if (snapshot.testType == "alarm") {
            ReminderDebugStageRow(R.string.reminder_debug_stage_alarm, snapshot.alarmScheduled)
            ReminderDebugStageRow(R.string.reminder_debug_stage_receiver, snapshot.receiverFired)
            ReminderDebugStageRow(R.string.reminder_debug_stage_worker_enqueue, snapshot.workerEnqueued)
            ReminderDebugStageRow(R.string.reminder_debug_stage_worker, snapshot.workerStarted)
        }
        ReminderDebugStageRow(
            R.string.reminder_debug_stage_notification_generation,
            snapshot.notificationGenerated
        )
        ReminderDebugStageRow(R.string.reminder_debug_stage_notification_post, snapshot.notificationPosted)
        if (snapshot.detail.isNotBlank()) {
            Text(
                text = stringResource(R.string.reminder_debug_result_detail, snapshot.detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReminderDebugStageRow(labelRes: Int, completed: Boolean) {
    Text(
        text = stringResource(
            R.string.reminder_debug_stage_format,
            stringResource(labelRes),
            stringResource(
                if (completed) R.string.reminder_debug_stage_completed
                else R.string.reminder_debug_stage_waiting
            )
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (completed) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
