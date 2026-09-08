from pathlib import Path

root = Path(__file__).resolve().parents[1]
targets = {
    "app/src/main/java/jp/linkserver/nittcsc/ui/NittcSchedulerApp.kt": (
        "TimetableScreen", "selectedDay", "dayLabels", "dayOfWeek =", "weekDates", "0L..4L", "Monday", "Friday"
    ),
    "app/src/main/java/jp/linkserver/nittcsc/ui/SettingsScreen.kt": (
        "LessonStartNotificationSettingsContent", "section_data_transfer", "AppSettingsScaffold", "onOpenLocalSync"
    ),
    "app/src/main/java/jp/linkserver/nittcsc/widget/WidgetDataHelper.kt": (
        "defaultDayType", "1..5"
    ),
}
for rel, patterns in targets.items():
    path = root / rel
    lines = path.read_text(encoding="utf-8").splitlines()
    print(f"\n## {rel}")
    shown = set()
    for i, line in enumerate(lines, 1):
        if any(p in line for p in patterns):
            start, end = max(1, i - 5), min(len(lines), i + 8)
            key = (start, end)
            if key in shown:
                continue
            shown.add(key)
            print(f"\n-- lines {start}-{end} --")
            for n in range(start, end + 1):
                print(f"{n}: {lines[n-1]}")

raise SystemExit("diagnostic pass 2 complete")
