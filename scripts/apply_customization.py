from pathlib import Path

root = Path(__file__).resolve().parents[1]
patterns = ("1..5", "DayOfWeek.SATURDAY", "DayOfWeek.SUNDAY", "LessonStartNotificationWorker.rescheduleAll", "SettingsScreen(")
for path in sorted((root / "app/src/main/java").rglob("*.kt")):
    text = path.read_text(encoding="utf-8")
    hits = []
    for pattern in patterns:
        for i, line in enumerate(text.splitlines(), 1):
            if pattern in line:
                hits.append((i, line.strip()))
    if hits:
        print(f"\n## {path.relative_to(root)}")
        for i, line in hits:
            print(f"{i}: {line}")

raise SystemExit("diagnostic pass complete; replace this script with the transformation after inspecting occurrences")
