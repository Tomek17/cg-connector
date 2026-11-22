"""Polish exercise list in the Android app must use exact names and keys from Garmin's catalog."""

import re
from pathlib import Path

import pytest
from garminconnect import exercises as catalog

CATALOG_KT = (
    Path(__file__).resolve().parents[2]
    / "android/app/src/main/java/pl/cgconnector/app/domain/workout/ExerciseCatalog.kt"
)
ENTRY = re.compile(r'CatalogExercise\(ExerciseGroup\.\w+, "[^"]+", "([^"]+)", "(\w+)", "(\w+)"\)')


def test_android_exercise_catalog() -> None:
    if not CATALOG_KT.exists():
        pytest.skip("Android sources not present (e.g. server-only Docker build)")
    entries = ENTRY.findall(CATALOG_KT.read_text(encoding="utf-8"))
    assert len(entries) >= 100
    by_name = {e["name"]: e for e in catalog.EXERCISES}
    for name, category, key in entries:
        assert name in by_name, f"{name!r} is not in Garmin's exercise catalog"
        assert (by_name[name]["category"], by_name[name]["exercise"]) == (category, key), name
