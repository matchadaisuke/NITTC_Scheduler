from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"pattern not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Gradle: app key comes from a developer Gradle property or environment variable.
build = ROOT / "app/build.gradle.kts"
replace_once(
    build,
    'val appVersionName = "1.1.0-IntDev"\n',
    '''val appVersionName = "1.1.0-IntDev"\nval megaAppKey = providers.gradleProperty("MEGA_APP_KEY")\n    .orElse(providers.environmentVariable("MEGA_APP_KEY"))\n    .orElse("")\n    .get()\nval megaAppKeyEscaped = megaAppKey.replace("\\\\", "\\\\\\\\").replace("\\\"", "\\\\\\\"")\n'''
)
replace_once(
    build,
    '        buildConfigField("String", "BUILD_NUMBER", "\\\"$generatedBuildNumber\\\"")\n',
    '        buildConfigField("String", "BUILD_NUMBER", "\\\"$generatedBuildNumber\\\"")\n'
    '        buildConfigField("String", "MEGA_APP_KEY", "\\\"$megaAppKeyEscaped\\\"")\n'
)
replace_once(
    build,
    'dependencies {\n',
    '''dependencies {\n    val megaSdkAar = file("libs/mega-sdk.aar")\n    if (megaSdkAar.exists()) {\n        implementation(files(megaSdkAar))\n    }\n    implementation("androidx.exifinterface:exifinterface:1.3.7")\n    implementation("org.jetbrains:annotations:24.1.0")\n\n'''
)

# Keep MEGA's SWIG/JNI-facing classes when release minification is enabled.
proguard = ROOT / "app/proguard-rules.pro"
proguard_text = proguard.read_text(encoding="utf-8")
keep_rule = "\n# MEGA SDK Java/SWIG bindings are accessed through reflection.\n-keep class nz.mega.sdk.** { *; }\n"
if "-keep class nz.mega.sdk.**" not in proguard_text:
    proguard.write_text(proguard_text.rstrip() + keep_rule, encoding="utf-8")

# Replace the old SAF picker UI with a MEGA login/session UI.
ui = ROOT / "app/src/main/java/jp/linkserver/nittcsc/ui/CustomizationSettingsContent.kt"
text = ui.read_text(encoding="utf-8")
text = text.replace("import android.net.Uri\n", "import android.os.Build\n")
text = text.replace("import androidx.activity.compose.rememberLauncherForActivityResult\n", "")
text = text.replace("import androidx.activity.result.contract.ActivityResultContracts\n", "")
if "import androidx.compose.ui.text.input.PasswordVisualTransformation\n" not in text:
    text = text.replace(
        "import androidx.compose.ui.platform.LocalContext\n",
        "import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.text.input.PasswordVisualTransformation\n"
    )

marker = "@Composable\ninternal fun CloudFileSyncSettingsContent() {"
pos = text.find(marker)
if pos < 0:
    raise RuntimeError("CloudFileSyncSettingsContent marker not found")

mega_ui = r'''@Composable
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MEGA同期", style = MaterialTheme.typography.titleMedium)
            Text(
                "MEGAアカウントへアプリ内でログインし、時間割・詳細時刻・設定・通知設定を自動同期します。ファイル選択やMEGAアプリは不要です。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> {
                    Text("MEGA公式SDKの対応範囲に合わせ、MEGA同期はAndroid 9以降で利用できます。")
                }
                !sdkAvailable -> {
                    Text("このビルドにはMEGA SDKが含まれていません。app/libs/mega-sdk.aar を組み込んだビルドが必要です。")
                }
                !appKeyConfigured -> {
                    Text("このビルドにはMEGA App Keyが設定されていません。開発者側でMEGA_APP_KEYを設定してください。")
                }
                !configured -> {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("MEGAメールアドレス") },
                        singleLine = true,
                        enabled = !busy
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("パスワード") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("2段階認証コード（必要な場合）") },
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
                        Text(if (busy) "接続中…" else "MEGAにログインして同期")
                    }
                    Text(
                        "パスワードは保存しません。ログイン成功後はMEGAのセッションキーを端末に保持し、次回から自動接続します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text("接続中: ${CloudFileSyncManager.linkedEmail(context).orEmpty()}")
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
                            Text(if (busy) "同期中…" else "今すぐ同期")
                        }
                        TextButton(
                            onClick = {
                                CloudFileSyncManager.disconnect(context)
                                configured = false
                                status = "未設定"
                            },
                            enabled = !busy
                        ) {
                            Text("ログアウト / 解除")
                        }
                    }
                    Text(
                        "MEGA上の「NITTC Scheduler/scheduler-sync.json」を自動管理します。アプリ実行中はローカルDBとMEGAの変更イベントで同期し、バックグラウンドはWorkManagerで取りこぼしを補完します。",
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
'''
ui.write_text(text[:pos] + mega_ui, encoding="utf-8")

# Developer setup note.
readme = ROOT / "README.md"
readme_text = readme.read_text(encoding="utf-8")
section = r'''

## MEGA standalone sync (custom fork)

This fork can synchronize its full scheduler JSON directly with a MEGA account. End users only
need to sign in with their MEGA email/password (and MFA code when enabled); no Android file picker
or separate MEGA app is required. The password is not persisted. After login the MEGA session is
stored and reused.

Developer setup:

1. Create a MEGA SDK application key for this app.
2. Set `MEGA_APP_KEY=<key>` in `~/.gradle/gradle.properties`, or export it as an environment variable.
3. Put the official MEGA Android SDK AAR at `app/libs/mega-sdk.aar` (the repository workflow can build
   this from the pinned official MEGA SDK source).

The MEGA sync feature is exposed on Android 9+ because that is the minimum Android version currently
supported by the official MEGA Android SDK. The rest of NITTC Scheduler keeps its existing minSdk.
'''
if "## MEGA standalone sync (custom fork)" not in readme_text:
    readme.write_text(readme_text.rstrip() + section + "\n", encoding="utf-8")

print("MEGA sync UI/build wiring applied")
