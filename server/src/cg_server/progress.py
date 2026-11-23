"""Data for the Progress tab: activities with the same tag compared over time.

Running: pace, average HR and cadence from the work laps only (Garmin lap intensity
other than warm-up, cool-down, recovery and rest; a run without such laps counts whole),
plus the whole distance. Strength: every active set of every exercise as one point.
Climbing: per session the hardest sent boulder and sent/failed attempts per grade, from
Garmin typed splits (CLIMB_ACTIVE with status CLIMB_COMPLETED / CLIMB_ATTEMPTED).
"""

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date

from sqlalchemy import ColumnElement, func, or_, select
from sqlalchemy.orm import Session, selectinload

from cg_server import queries
from cg_server.models import Activity, ActivityKind
from cg_server.tags import TAGS, tags_for

NOT_WORK = {"WARMUP", "COOLDOWN", "RECOVERY", "REST"}


@dataclass(frozen=True)
class TagCount:
    tag: str
    kind: ActivityKind
    count: int
    last_day: date


@dataclass(frozen=True)
class RunPoint:
    activity_id: int
    day: date
    name: str | None
    distance_m: float | None
    pace_s_per_km: float | None
    avg_hr: float | None
    avg_cadence_spm: float | None
    # Laps used for pace/HR/cadence; 0 = the whole activity (no work laps found).
    work_laps: int


@dataclass(frozen=True)
class SetPoint:
    activity_id: int
    day: date
    set_number: int  # 1.. within this exercise in this activity
    weight_kg: float | None
    reps: int | None


@dataclass
class ExerciseProgress:
    category: str | None
    exercise: str
    points: list[SetPoint] = field(default_factory=list)

    @property
    def sessions(self) -> int:
        return len({p.activity_id for p in self.points})


def _implicit_tag(kind: ActivityKind) -> str | None:
    """The only tag of a kind (climbing: wall) also covers its untagged activities, so the
    Progress tab shows every session without tagging each one."""
    tags = tags_for(kind)
    return tags[0] if len(tags) == 1 else None


def _tag_filter(tag: str) -> ColumnElement[bool]:
    kind = TAGS[tag]
    if _implicit_tag(kind) == tag:
        return (Activity.kind == kind) & or_(Activity.tag == tag, Activity.tag.is_(None))
    return Activity.tag == tag


def tag_counts(session: Session, *, user_id: int) -> list[TagCount]:
    """Tags in use with the number of activities and the newest local day, in TAGS order."""
    user_id = queries.check_user_id(user_id)
    rows = session.execute(
        select(Activity.tag, func.count(), func.max(Activity.start_time_local))
        .where(Activity.user_id == user_id, Activity.tag.is_not(None))
        .group_by(Activity.tag)
    ).all()
    found = {tag: (count, last) for tag, count, last in rows if tag in TAGS}
    for kind in ActivityKind:
        tag = _implicit_tag(kind)
        if tag is None:
            continue
        count, last = session.execute(
            select(func.count(), func.max(Activity.start_time_local)).where(
                Activity.user_id == user_id, _tag_filter(tag)
            )
        ).one()
        if count:
            found[tag] = (count, last)
    return [
        TagCount(tag, TAGS[tag], found[tag][0], found[tag][1].date())
        for tag in TAGS
        if tag in found
    ]


def tagged(session: Session, *, user_id: int, tag: str) -> list[Activity]:
    """The user's activities with `tag`, oldest first, details loaded."""
    return list(
        session.scalars(
            select(Activity)
            .where(Activity.user_id == queries.check_user_id(user_id), _tag_filter(tag))
            .options(selectinload(Activity.details))
            .order_by(Activity.start_time_utc, Activity.id)
        )
    )


def _weighted(values: list[tuple[float | None, float]]) -> float | None:
    present = [(v, w) for v, w in values if v is not None and w > 0]
    total = sum(w for _, w in present)
    return sum(v * w for v, w in present) / total if total else None


def run_point(activity: Activity) -> RunPoint:
    laps = queries.parse_details(activity).laps
    work = [
        lap
        for lap in laps
        if lap.intensity is not None
        and lap.intensity.upper() not in NOT_WORK
        and (lap.duration_s or 0) > 0
    ]
    if work and len(work) < len(laps):
        time = sum(lap.duration_s or 0 for lap in work)
        dist = sum(lap.distance_m or 0 for lap in work)
        pace = time / dist * 1000 if dist > 0 else None
        hr = _weighted([(lap.avg_hr, lap.duration_s or 0) for lap in work])
        cadence = _weighted([(lap.avg_cadence_spm, lap.duration_s or 0) for lap in work])
        used = len(work)
    else:
        pace, hr, cadence = activity.avg_pace_s_per_km, activity.avg_hr, activity.avg_cadence_spm
        used = 0
    return RunPoint(
        activity_id=activity.garmin_activity_id,
        day=activity.start_time_local.date(),
        name=activity.name,
        distance_m=activity.distance_m,
        pace_s_per_km=pace,
        avg_hr=hr,
        avg_cadence_spm=cadence,
        work_laps=used,
    )


def running_progress(session: Session, *, user_id: int, tag: str) -> list[RunPoint]:
    return [run_point(a) for a in tagged(session, user_id=user_id, tag=tag)]


def strength_progress(session: Session, *, user_id: int, tag: str) -> list[ExerciseProgress]:
    """Exercises done in activities with `tag`, most sessions first; each active set a point."""
    by_exercise: dict[tuple[str | None, str], ExerciseProgress] = {}
    for activity in tagged(session, user_id=user_id, tag=tag):
        numbers: dict[tuple[str | None, str], int] = defaultdict(int)
        for s in queries.parse_details(activity).exercise_sets:
            if s.exercise is None or (s.set_type or "ACTIVE").upper() != "ACTIVE":
                continue
            if s.reps is None and s.weight_kg is None:
                continue
            key = (s.category, s.exercise)
            numbers[key] += 1
            progress = by_exercise.setdefault(key, ExerciseProgress(s.category, s.exercise))
            progress.points.append(
                SetPoint(
                    activity_id=activity.garmin_activity_id,
                    day=activity.start_time_local.date(),
                    set_number=numbers[key],
                    weight_kg=s.weight_kg,
                    reps=s.reps,
                )
            )
    return sorted(by_exercise.values(), key=lambda e: (-e.sessions, e.exercise))


@dataclass(frozen=True)
class GradeCount:
    grade: str  # Garmin valueKey, e.g. V4
    sort: int  # Garmin sortOrder: higher = harder
    sent: int
    failed: int


@dataclass(frozen=True)
class ClimbSession:
    activity_id: int
    day: date
    name: str | None
    top_grade: str | None  # hardest sent boulder
    top_sort: int | None
    sent: int
    failed: int
    grades: list[GradeCount]  # easiest first


def climb_session(activity: Activity) -> ClimbSession | None:
    """None when the session has no typed splits with climbs (not fetched or no data)."""
    raw = activity.details.typed_splits_json if activity.details else None
    splits = raw.get("splits") if isinstance(raw, dict) else None
    if not isinstance(splits, list):
        return None
    counts: dict[tuple[int, str], list[int]] = {}
    for split in splits:
        if not isinstance(split, dict) or split.get("type") != "CLIMB_ACTIVE":
            continue
        grade = split.get("gradeValue")
        if not isinstance(grade, dict) or not isinstance(grade.get("valueKey"), str):
            continue
        sort = grade.get("sortOrder")
        key = (sort if isinstance(sort, int) else 0, grade["valueKey"])
        sent_failed = counts.setdefault(key, [0, 0])
        sent_failed[0 if split.get("status") == "CLIMB_COMPLETED" else 1] += 1
    if not counts:
        return None
    grades = [GradeCount(g, s, c[0], c[1]) for (s, g), c in sorted(counts.items())]
    top = max((g for g in grades if g.sent), key=lambda g: g.sort, default=None)
    return ClimbSession(
        activity_id=activity.garmin_activity_id,
        day=activity.start_time_local.date(),
        name=activity.name,
        top_grade=top.grade if top else None,
        top_sort=top.sort if top else None,
        sent=sum(g.sent for g in grades),
        failed=sum(g.failed for g in grades),
        grades=grades,
    )


def climbing_progress(session: Session, *, user_id: int, tag: str) -> list[ClimbSession]:
    sessions = (climb_session(a) for a in tagged(session, user_id=user_id, tag=tag))
    return [s for s in sessions if s is not None]
