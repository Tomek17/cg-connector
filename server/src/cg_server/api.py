"""REST API for the Android app (`/api/v1`). Auth: `Authorization: Bearer <app token>`.

The user always comes from the token; every read goes through `cg_server.queries` with
that user's id. Units: m, s, bpm, W, steps/min; times ISO-8601 with the Europe/Warsaw
offset; dates are local (Europe/Warsaw) calendar days. Contract: docs/api/README.md.
"""

from collections.abc import Callable, Iterator
from datetime import date, datetime, timedelta
from typing import Annotated
from zoneinfo import ZoneInfo

from fastapi import (
    APIRouter,
    BackgroundTasks,
    Depends,
    HTTPException,
    Path,
    Query,
    Request,
    status,
)
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session, sessionmaker

from cg_server import goals, manual_sync, progress, queries
from cg_server.models import Activity, ActivityKind, Goal, User, utcnow
from cg_server.queries import Period
from cg_server.tags import TAGS, TagError, set_tag
from cg_server.user_secrets import SecretKind, user_for_secret

bearer = HTTPBearer(auto_error=False)

DEFAULT_LIST_DAYS = 365
MAX_LIST_LIMIT = 2000
DEFAULT_SUMMARY_PERIODS = 12


def db_session(request: Request) -> Iterator[Session]:
    sessions: sessionmaker[Session] = request.app.state.sessions
    with sessions() as session:
        yield session


def local_tz(request: Request) -> ZoneInfo:
    tz: ZoneInfo = request.app.state.tz
    return tz


def current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    session: Annotated[Session, Depends(db_session)],
) -> User:
    """The user owning the app token. Every data query must filter by this user."""
    user = None
    if credentials is not None:
        user = user_for_secret(session, SecretKind.APP_TOKEN, credentials.credentials)
    if user is None:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            detail="Nieprawidłowy token aplikacji",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return user


CurrentUser = Annotated[User, Depends(current_user)]
DbSession = Annotated[Session, Depends(db_session)]
LocalTz = Annotated[ZoneInfo, Depends(local_tz)]
FromDate = Annotated[
    date | None, Query(alias="from", description="Pierwszy dzień (lokalnie), włącznie")
]
ToDate = Annotated[
    date | None, Query(alias="to", description="Ostatni dzień (lokalnie), włącznie; domyślnie dziś")
]


RANGE_ERRORS = {
    queries.RangeProblem.OUT_OF_BOUNDS: "Daty muszą być między 2000-01-01 a dziś + 1 rok",
    queries.RangeProblem.REVERSED: "Parametr 'from' nie może być późniejszy niż 'to'",
    queries.RangeProblem.TOO_LONG: "Zakres dłuższy niż 2 lata",
}


def _invalid(detail: str) -> HTTPException:
    return HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, detail=detail)


def _range(
    start: date | None, end: date | None, tz: ZoneInfo, default_start: Callable[[date], date]
) -> tuple[date, date]:
    """[start, end] with defaults: `end` = today (local), `start` = default_start(end)."""
    try:
        return queries.resolve_range(start, end, utcnow().astimezone(tz).date(), default_start)
    except queries.InvalidRange as e:
        raise _invalid(RANGE_ERRORS[e.problem]) from e


def _days_before(end: date) -> date:
    return end - timedelta(days=DEFAULT_LIST_DAYS)


def _int(value: float | None) -> int | None:
    return round(value) if value is not None else None


def _pace_min(seconds_per_km: float | None) -> float | None:
    return round(seconds_per_km / 60.0, 3) if seconds_per_km else None


# --- models -------------------------------------------------------------------------------


class Me(BaseModel):
    name: str
    display_name: str
    garmin_status: str | None = Field(description="ok | needs_login | rate_limited | null")
    last_sync_at: datetime | None = Field(description="Ostatnia udana synchronizacja z Garmin")
    last_sync_attempt_at: datetime | None = Field(description="Początek ostatniej próby syncu")
    last_sync_status: str | None = Field(description="Status ostatniej próby syncu")
    latest_activity_date: date | None = Field(description="Dzień najnowszej aktywności")


class ActivitySummary(BaseModel):
    id: int = Field(description="ID aktywności w Garmin Connect (stabilny klucz)")
    type: ActivityKind
    type_key: str = Field(description="Surowy typ z Garmina, np. trail_running")
    name: str | None
    start_time: datetime
    duration_sec: int
    moving_duration_sec: int | None
    distance_m: float | None
    avg_hr: int | None
    max_hr: int | None
    elevation_gain_m: float | None
    avg_power_w: int | None
    avg_cadence_spm: int | None
    avg_speed_mps: float | None
    pace_min_per_km: float | None = Field(description="Średnie tempo (tylko bieg), min/km")
    aerobic_training_effect: float | None
    anaerobic_training_effect: float | None
    training_load: float | None
    tag: str | None = Field(
        default=None, description="Tag (jeden): " + ", ".join(TAGS) + "; null = brak"
    )


class ActivityList(BaseModel):
    items: list[ActivitySummary]
    has_more: bool = Field(description="Jest więcej aktywności niż `limit` w tym przedziale")


class Lap(BaseModel):
    index: int
    distance_m: float | None
    duration_sec: float | None
    avg_hr: int | None
    max_hr: int | None
    avg_speed_mps: float | None
    pace_min_per_km: float | None
    elevation_gain_m: float | None
    avg_power_w: int | None
    avg_cadence_spm: int | None


class HrZone(BaseModel):
    zone: int
    seconds: float
    low_bpm: int | None


class ExerciseSet(BaseModel):
    set_type: str | None = Field(description="ACTIVE | REST")
    category: str | None
    exercise: str | None
    reps: int | None
    weight_kg: float | None
    duration_sec: float | None


class ActivityDetail(ActivitySummary):
    details_available: bool = Field(description="Szczegóły pobrane z Garmina")
    laps: list[Lap]
    hr_zones: list[HrZone]
    exercise_sets: list[ExerciseSet]


class TagUpdate(BaseModel):
    tag: str | None = Field(default=None, description="Klucz tagu; null albo brak pola usuwa tag")


class TagCountOut(BaseModel):
    tag: str
    type: ActivityKind = Field(description="Rodzaj aktywności, do którego należy tag")
    count: int
    last_date: date


class ProgressTags(BaseModel):
    tags: list[TagCountOut] = Field(description="Tagi w użyciu, w stałej kolejności")
    available: dict[str, ActivityKind] = Field(description="Wszystkie tagi i ich rodzaj")


class RunPointOut(BaseModel):
    activity_id: int
    date: date
    name: str | None
    distance_m: float | None
    pace_min_per_km: float | None = Field(description="Z odcinków roboczych, bez rozgrzewki")
    avg_hr: int | None
    avg_cadence_spm: int | None
    work_laps: int = Field(description="Użyte okrążenia robocze; 0 = cała aktywność")


class SetPointOut(BaseModel):
    activity_id: int
    date: date
    set_number: int
    weight_kg: float | None
    reps: int | None


class ExerciseProgressOut(BaseModel):
    category: str | None
    exercise: str = Field(description="Nazwa ćwiczenia z Garmina (klucz)")
    sessions: int
    sets: list[SetPointOut]


class TagProgress(BaseModel):
    tag: str
    type: ActivityKind
    running: list[RunPointOut] = Field(description="Bieg: punkt na aktywność, od najstarszej")
    strength: list[ExerciseProgressOut] = Field(description="Siłownia: ćwiczenia z seriami")


class GoalBody(BaseModel):
    name: str = Field(description="Wydarzenie, np. Półmaraton Warszawski")
    date: date
    distance_km: float = Field(description="Dystans startu w km, np. 21.0975")
    target_seconds: int | None = Field(default=None, description="Czas docelowy w sekundach")


class GoalOut(BaseModel):
    name: str
    date: date
    distance_km: float
    target_seconds: int | None
    target_pace_min_per_km: float | None = Field(description="Tempo docelowe, minuty dziesiętnie")
    days_left: int = Field(description="Dni do startu (ujemne po starcie)")


class GoalState(BaseModel):
    goal: GoalOut | None


def goal_state(goal: Goal | None, tz: ZoneInfo) -> GoalState:
    if goal is None:
        return GoalState(goal=None)
    pace = goals.pace_s_per_km(goal)
    return GoalState(
        goal=GoalOut(
            name=goal.name,
            date=goal.event_date,
            distance_km=goal.distance_km,
            target_seconds=goal.target_seconds,
            target_pace_min_per_km=_pace_min(pace),
            days_left=(goal.event_date - utcnow().astimezone(tz).date()).days,
        )
    )


class Vo2MaxPoint(BaseModel):
    date: date
    value: float
    sport: str


class Vo2MaxList(BaseModel):
    items: list[Vo2MaxPoint]


class SummaryItem(BaseModel):
    period_start: date
    period_end: date
    type: ActivityKind
    count: int
    distance_m: float | None
    duration_sec: int
    elevation_gain_m: float | None
    avg_pace_min_per_km: float | None = Field(description="Tylko bieg")


class Summary(BaseModel):
    period: Period
    from_: date = Field(alias="from")
    to: date
    items: list[SummaryItem]


def activity_summary(activity: Activity, tz: ZoneInfo) -> dict[str, object]:
    return {
        "id": activity.garmin_activity_id,
        "type": activity.kind,
        "type_key": activity.type_key,
        "name": activity.name,
        "start_time": queries.as_utc(activity.start_time_utc).astimezone(tz),
        "duration_sec": round(activity.duration_s or 0),
        "moving_duration_sec": _int(activity.moving_duration_s),
        "distance_m": activity.distance_m,
        "avg_hr": _int(activity.avg_hr),
        "max_hr": _int(activity.max_hr),
        "elevation_gain_m": activity.elevation_gain_m,
        "avg_power_w": _int(activity.avg_power_w),
        "avg_cadence_spm": _int(activity.avg_cadence_spm),
        "avg_speed_mps": activity.avg_speed_mps,
        "pace_min_per_km": _pace_min(activity.avg_pace_s_per_km),
        "aerobic_training_effect": activity.aerobic_training_effect,
        "anaerobic_training_effect": activity.anaerobic_training_effect,
        "training_load": activity.training_load,
        "tag": activity.tag,
    }


# --- routes -------------------------------------------------------------------------------

router = APIRouter(prefix="/api/v1")


@router.get("/me")
def me(user: CurrentUser, session: DbSession, tz: LocalTz) -> Me:
    state = queries.sync_status(session, user_id=user.id, tz=tz)
    return Me(
        name=user.name,
        display_name=user.name,
        garmin_status=state.garmin_status.value if state.garmin_status else None,
        last_sync_at=state.last_success_at.astimezone(tz) if state.last_success_at else None,
        last_sync_attempt_at=state.last_attempt_at.astimezone(tz)
        if state.last_attempt_at
        else None,
        last_sync_status=state.last_status.value if state.last_status else None,
        latest_activity_date=state.latest_activity_date,
    )


@router.get("/activities")
def list_activities(
    user: CurrentUser,
    session: DbSession,
    tz: LocalTz,
    start: FromDate = None,
    end: ToDate = None,
    kind: Annotated[ActivityKind | None, Query(alias="type")] = None,
    limit: Annotated[int, Query(ge=1, le=MAX_LIST_LIMIT)] = MAX_LIST_LIMIT,
) -> ActivityList:
    """Aktywności z dni [from, to] (domyślnie ostatnie 365 dni), od najnowszej."""
    start, end = _range(start, end, tz, _days_before)
    rows = queries.list_activities(
        session, user_id=user.id, start=start, end=end, kind=kind, limit=limit + 1, tz=tz
    )
    return ActivityList.model_validate(
        {
            "items": [activity_summary(a, tz) for a in rows[:limit]],
            "has_more": len(rows) > limit,
        }
    )


@router.get("/activities/{activity_id}", responses={404: {"description": "Brak aktywności"}})
def get_activity(
    user: CurrentUser,
    session: DbSession,
    tz: LocalTz,
    activity_id: Annotated[int, Path(description="ID aktywności w Garmin Connect")],
) -> ActivityDetail:
    """Szczegóły aktywności: okrążenia, czas w strefach tętna, serie siłowe."""
    activity = queries.get_activity(session, user_id=user.id, activity_id=activity_id)
    if activity is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="Nie znaleziono aktywności")
    details = queries.parse_details(activity)
    return ActivityDetail.model_validate(
        {
            **activity_summary(activity, tz),
            "details_available": details.available,
            "laps": [
                {
                    "index": lap.index,
                    "distance_m": lap.distance_m,
                    "duration_sec": lap.duration_s,
                    "avg_hr": _int(lap.avg_hr),
                    "max_hr": _int(lap.max_hr),
                    "avg_speed_mps": lap.avg_speed_mps,
                    "pace_min_per_km": _pace_min(lap.pace_s_per_km),
                    "elevation_gain_m": lap.elevation_gain_m,
                    "avg_power_w": _int(lap.avg_power_w),
                    "avg_cadence_spm": _int(lap.avg_cadence_spm),
                }
                for lap in details.laps
            ],
            "hr_zones": [
                {"zone": z.zone, "seconds": z.seconds, "low_bpm": z.low_bpm}
                for z in details.hr_zones
            ],
            "exercise_sets": [
                {
                    "set_type": s.set_type,
                    "category": s.category,
                    "exercise": s.exercise,
                    "reps": s.reps,
                    "weight_kg": s.weight_kg,
                    "duration_sec": s.duration_s,
                }
                for s in details.exercise_sets
            ],
        }
    )


@router.put(
    "/activities/{activity_id}/tag",
    responses={404: {"description": "Brak aktywności"}, 422: {"description": "Zły tag"}},
)
def put_activity_tag(
    body: TagUpdate,
    user: CurrentUser,
    session: DbSession,
    tz: LocalTz,
    activity_id: Annotated[int, Path(description="ID aktywności w Garmin Connect")],
) -> ActivitySummary:
    """Ustawia (albo z `null` usuwa) tag aktywności. Tag musi pasować do jej rodzaju."""
    if queries.get_activity(session, user_id=user.id, activity_id=activity_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="Nie znaleziono aktywności")
    try:
        activity = set_tag(session, user_id=user.id, activity_id=activity_id, tag=body.tag)
    except TagError as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(e)) from None
    return ActivitySummary.model_validate(activity_summary(activity, tz))


@router.get("/progress")
def progress_tags(user: CurrentUser, session: DbSession) -> ProgressTags:
    """Tagi w użyciu (liczba aktywności, ostatni dzień) i lista wszystkich tagów."""
    return ProgressTags(
        tags=[
            TagCountOut(tag=t.tag, type=t.kind, count=t.count, last_date=t.last_day)
            for t in progress.tag_counts(session, user_id=user.id)
        ],
        available=dict(TAGS),
    )


@router.get("/progress/{tag}", responses={404: {"description": "Nieznany tag"}})
def progress_tag(
    user: CurrentUser,
    session: DbSession,
    tag: Annotated[str, Path(description="Klucz tagu, np. intervals")],
) -> TagProgress:
    """Dane do wykresów dla aktywności z tym tagiem (od najstarszej)."""
    kind = TAGS.get(tag)
    if kind is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="Nieznany tag")
    running = (
        progress.running_progress(session, user_id=user.id, tag=tag)
        if kind == ActivityKind.RUNNING
        else []
    )
    strength = (
        progress.strength_progress(session, user_id=user.id, tag=tag)
        if kind == ActivityKind.STRENGTH
        else []
    )
    return TagProgress(
        tag=tag,
        type=kind,
        running=[
            RunPointOut(
                activity_id=p.activity_id,
                date=p.day,
                name=p.name,
                distance_m=p.distance_m,
                pace_min_per_km=_pace_min(p.pace_s_per_km),
                avg_hr=_int(p.avg_hr),
                avg_cadence_spm=_int(p.avg_cadence_spm),
                work_laps=p.work_laps,
            )
            for p in running
        ],
        strength=[
            ExerciseProgressOut(
                category=e.category,
                exercise=e.exercise,
                sessions=e.sessions,
                sets=[
                    SetPointOut(
                        activity_id=p.activity_id,
                        date=p.day,
                        set_number=p.set_number,
                        weight_kg=p.weight_kg,
                        reps=p.reps,
                    )
                    for p in e.points
                ],
            )
            for e in strength
        ],
    )


@router.get("/goal")
def get_goal(user: CurrentUser, session: DbSession, tz: LocalTz) -> GoalState:
    """Cel użytkownika (start i czas docelowy) albo `goal: null`."""
    return goal_state(goals.get_goal(session, user.id), tz)


@router.put("/goal", responses={422: {"description": "Zły cel"}})
def put_goal(body: GoalBody, user: CurrentUser, session: DbSession, tz: LocalTz) -> GoalState:
    """Ustawia (zastępuje) cel. Ten sam cel widzi Claude."""
    try:
        goal = goals.set_goal(
            session,
            user.id,
            goals.GoalIn(body.name, body.date, body.distance_km, body.target_seconds),
        )
    except goals.GoalError as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(e)) from None
    return goal_state(goal, tz)


@router.delete("/goal")
def delete_goal(user: CurrentUser, session: DbSession) -> GoalState:
    """Usuwa cel."""
    goals.clear_goal(session, user.id)
    return GoalState(goal=None)


class SyncNow(BaseModel):
    """`started`: sync runs in the background (check `/me` for its result). Otherwise
    `reason`: no_garmin | needs_login | rate_limited | running | too_soon."""

    started: bool
    reason: str | None = None
    retry_at: datetime | None = None


@router.post("/sync")
def sync_now(
    request: Request, tasks: BackgroundTasks, user: CurrentUser, session: DbSession, tz: LocalTz
) -> SyncNow:
    """Pobiera nowe dane z Garmina teraz, poza codzienną synchronizacją."""
    result = manual_sync.check(session, user)
    if result.refusal is not None:
        retry = result.retry_at.astimezone(tz) if result.retry_at else None
        return SyncNow(started=False, reason=result.refusal.value, retry_at=retry)
    runner: manual_sync.ManualSync | None = getattr(request.app.state, "manual_sync", None)
    if runner is not None:
        tasks.add_task(runner.run, user.id)
    return SyncNow(started=runner is not None, reason=None if runner else "running")


@router.get("/vo2max")
def vo2max(
    user: CurrentUser,
    session: DbSession,
    tz: LocalTz,
    start: FromDate = None,
    end: ToDate = None,
) -> Vo2MaxList:
    """Wartości VO2max z dni [from, to] (domyślnie ostatnie 365 dni), od najstarszej."""
    start, end = _range(start, end, tz, _days_before)
    rows = queries.vo2max_trend(session, user_id=user.id, start=start, end=end, sport=None)
    return Vo2MaxList(items=[Vo2MaxPoint(date=r.date, value=r.value, sport=r.sport) for r in rows])


@router.get("/summary")
def summary(
    user: CurrentUser,
    session: DbSession,
    tz: LocalTz,
    period: Period = Period.WEEK,
    start: FromDate = None,
    end: ToDate = None,
) -> Summary:
    """Sumy per tydzień (od poniedziałku) lub miesiąc i typ aktywności, dla aktywności z dni
    [from, to]. Domyślnie 12 ostatnich pełnych okresów."""

    def default_start(end_day: date) -> date:
        first = queries.period_start(end_day, period)
        for _ in range(DEFAULT_SUMMARY_PERIODS - 1):
            first = queries.period_start(first - timedelta(days=1), period)
        return first

    start, end = _range(start, end, tz, default_start)
    rows = queries.training_summary(
        session, user_id=user.id, period=period, start=start, end=end, tz=tz
    )
    return Summary.model_validate(
        {
            "period": period,
            "from": start,
            "to": end,
            "items": [
                {
                    "period_start": r.period_start,
                    "period_end": r.period_end,
                    "type": r.kind,
                    "count": r.count,
                    "distance_m": r.distance_m,
                    "duration_sec": round(r.duration_s),
                    "elevation_gain_m": r.elevation_gain_m,
                    "avg_pace_min_per_km": _pace_min(r.pace_s_per_km),
                }
                for r in rows
            ],
        }
    )
