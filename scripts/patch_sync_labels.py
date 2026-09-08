from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text and old not in text:
        print(f"already patched: {path}")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"expected exactly one match in {path}, found {count}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    ROOT / "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt",
    "contentDescription = stringResource(R.string.cd_open_local_sync)",
    "contentDescription = stringResource(R.string.cd_open_mega_sync)",
)

settings = ROOT / "app/src/main/java/jp/linkserver/nittcsc/ui/SettingsScreen.kt"
replace_once(
    settings,
    'title = "旧・端末間同期",',
    'title = stringResource(R.string.settings_legacy_local_sync_title),',
)
replace_once(
    settings,
    'summary = "本家のWi-Fi / Nearby / 信頼済み端末による同期を利用します。",',
    'summary = stringResource(R.string.settings_legacy_local_sync_summary),',
)

print("sync labels patched")
