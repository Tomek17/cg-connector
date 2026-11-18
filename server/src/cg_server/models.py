"""SQLAlchemy ORM models. Schema changes always go with an Alembic migration."""

from datetime import UTC, date, datetime
from enum import StrEnum
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    Date,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def utcnow() -> datetime:
    return datetime.now(UTC)


class Base(DeclarativeBase):
    pass


def _str_enum(enum: type[StrEnum], name: str, length: int = 16) -> Enum:
    return Enum(
        enum,
        name=name,
        native_enum=False,
        create_constraint=True,
        length=length,
        values_callable=lambda e: [m.value for m in e],
    )


class GarminStatus(StrEnum):
    OK = "ok"
    NEEDS_LOGIN = "needs_login"
    RATE_LIMITED = "rate_limited"


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(32), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    garmin_credentials: Mapped["GarminCredentials | None"] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )


class GarminCredentials(Base):
    """Encrypted Garmin DI tokens (`client.dumps()`). Never the password."""

    __tablename__ = "garmin_credentials"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    encrypted_tokens: Mapped[str | None] = mapped_column(Text)
    status: Mapped[GarminStatus] = mapped_column(
        Enum(
            GarminStatus,
            name="garmin_status",
            native_enum=False,
            create_constraint=True,
            length=16,
            values_callable=lambda e: [m.value for m in e],
        )
    )
    # Last HTTP 429 from Garmin; password login is refused for 24 h after it.
    rate_limited_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )

    user: Mapped[User] = relationship(back_populates="garmin_credentials")


class UserSecret(Base):
    """Personal secret of a user: CG code (for OAuth login) or app token (Android REST).

    Only the SHA-256 hash is stored; one secret per kind (issuing a new one replaces it).
    """

    __tablename__ = "user_secrets"
    __table_args__ = (UniqueConstraint("user_id", "kind"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(16))
    secret_hash: Mapped[str] = mapped_column(String(64), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class OAuthClient(Base):
    """OAuth client registered via DCR (RFC 7591), e.g. claude.ai."""

    __tablename__ = "oauth_clients"

    client_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    # Client metadata as JSON, without the client secret.
    metadata_json: Mapped[str] = mapped_column(Text)
    # Fernet-encrypted client secret (confidential clients only).
    client_secret_encrypted: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class OAuthCode(Base):
    """Single-use authorization code (hash only), bound to client, user and PKCE challenge."""

    __tablename__ = "oauth_codes"

    code_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    client_id: Mapped[str] = mapped_column(
        ForeignKey("oauth_clients.client_id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    scopes: Mapped[str] = mapped_column(Text)
    code_challenge: Mapped[str] = mapped_column(String(128))
    redirect_uri: Mapped[str] = mapped_column(Text)
    redirect_uri_provided_explicitly: Mapped[bool] = mapped_column(Boolean)
    resource: Mapped[str | None] = mapped_column(Text)
    expires_at: Mapped[int] = mapped_column(Integer)  # unix time
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class OAuthToken(Base):
    """Access or refresh token (hash only). A pair issued together shares `grant_id`."""

    __tablename__ = "oauth_tokens"

    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    kind: Mapped[str] = mapped_column(String(16))  # "access" | "refresh"
    grant_id: Mapped[str] = mapped_column(String(64), index=True)
    client_id: Mapped[str] = mapped_column(
        ForeignKey("oauth_clients.client_id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    scopes: Mapped[str] = mapped_column(Text)
    resource: Mapped[str | None] = mapped_column(Text)
    expires_at: Mapped[int] = mapped_column(Integer)  # unix time
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class ActivityKind(StrEnum):
    """Normalized activity type; the raw Garmin `typeKey` is kept next to it."""

    RUNNING = "running"
    STRENGTH = "strength"
    CLIMBING = "climbing"
    OTHER = "other"


class SyncStatus(StrEnum):
    RUNNING = "running"  # in progress, or interrupted (e.g. laptop went to sleep)
    OK = "ok"
    PARTIAL = "partial"  # stopped by an error after saving some data
    FAILED = "failed"
    SKIPPED_RATE_LIMITED = "skipped_rate_limited"  # 24 h pause after HTTP 429


class Activity(Base):
    """Summary of one Garmin activity (from the activity list). Times: UTC + local wall clock."""

    __tablename__ = "activities"
    __table_args__ = (
        UniqueConstraint("user_id", "garmin_activity_id"),
        Index("ix_activities_user_start", "user_id", "start_time_utc"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    garmin_activity_id: Mapped[int] = mapped_column(BigInteger)
    name: Mapped[str | None] = mapped_column(String(200))
    kind: Mapped[ActivityKind] = mapped_column(_str_enum(ActivityKind, "activity_kind"))
    type_key: Mapped[str] = mapped_column(String(64))
    start_time_utc: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    start_time_local: Mapped[datetime] = mapped_column(DateTime())
    duration_s: Mapped[float | None] = mapped_column(Float)
    moving_duration_s: Mapped[float | None] = mapped_column(Float)
    distance_m: Mapped[float | None] = mapped_column(Float)
    avg_hr: Mapped[float | None] = mapped_column(Float)
    max_hr: Mapped[float | None] = mapped_column(Float)
    avg_speed_mps: Mapped[float | None] = mapped_column(Float)
    avg_pace_s_per_km: Mapped[float | None] = mapped_column(Float)
    elevation_gain_m: Mapped[float | None] = mapped_column(Float)
    avg_power_w: Mapped[float | None] = mapped_column(Float)
    avg_cadence_spm: Mapped[float | None] = mapped_column(Float)
    aerobic_training_effect: Mapped[float | None] = mapped_column(Float)
    anaerobic_training_effect: Mapped[float | None] = mapped_column(Float)
    training_load: Mapped[float | None] = mapped_column(Float)
    # Scalar fields of the Garmin summary without GPS/owner/device data.
    summary_json: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )

    user: Mapped[User] = relationship()
    details: Mapped["ActivityDetails | None"] = relationship(
        back_populates="activity", cascade="all, delete-orphan"
    )


class ActivityDetails(Base):
    """Laps/splits, HR time in zones and strength sets; fetched once, for new activities only."""

    __tablename__ = "activity_details"

    activity_id: Mapped[int] = mapped_column(
        ForeignKey("activities.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    laps_json: Mapped[Any] = mapped_column(JSON, nullable=True)
    hr_zones_json: Mapped[Any] = mapped_column(JSON, nullable=True)
    exercise_sets_json: Mapped[Any] = mapped_column(JSON, nullable=True)
    # Garmin returned an error (e.g. 404) for this activity's details; not retried.
    fetch_failed: Mapped[bool] = mapped_column(default=False)
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    activity: Mapped[Activity] = relationship(back_populates="details")


class Vo2Max(Base):
    __tablename__ = "vo2max"
    __table_args__ = (UniqueConstraint("user_id", "date", "sport"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    date: Mapped[date] = mapped_column(Date)
    sport: Mapped[str] = mapped_column(String(16))
    value: Mapped[float] = mapped_column(Float)


class SyncRun(Base):
    """One sync attempt for one user. Data from `backfill_date` (oldest local day synced)
    up to `cursor_date` (newest local day synced) is saved; taken as min/max over runs."""

    __tablename__ = "sync_runs"
    __table_args__ = (Index("ix_sync_runs_user_started", "user_id", "started_at"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    status: Mapped[SyncStatus] = mapped_column(_str_enum(SyncStatus, "sync_status", 24))
    cursor_date: Mapped[date | None] = mapped_column(Date)
    backfill_date: Mapped[date | None] = mapped_column(Date)
    activities_new: Mapped[int] = mapped_column(default=0)
    activities_updated: Mapped[int] = mapped_column(default=0)
    # Stopped at the per-run request limit: counts as today's sync, the rest comes next time.
    request_limit_reached: Mapped[bool] = mapped_column(default=False)
    # Short, fixed description of the failure; never response bodies or personal data.
    error: Mapped[str | None] = mapped_column(String(200))


class WorkoutSport(StrEnum):
    RUNNING = "running"
    STRENGTH = "strength"


class WorkoutOrigin(StrEnum):
    APP = "app"
    MCP = "mcp"


class Workout(Base):
    """A workout created in CG-connector (our definition, `cg_server.workout_definition`).

    `client_id` (UUID, unique per user) makes creation idempotent. `garmin_workout_id` is
    saved right after the upload, before scheduling, so a retry never uploads it twice.
    """

    __tablename__ = "workouts"
    __table_args__ = (UniqueConstraint("user_id", "client_id"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    client_id: Mapped[str] = mapped_column(String(36))
    name: Mapped[str] = mapped_column(String(60))
    sport: Mapped[WorkoutSport] = mapped_column(_str_enum(WorkoutSport, "workout_sport"))
    definition_json: Mapped[dict[str, Any]] = mapped_column(JSON)
    created_by: Mapped[WorkoutOrigin] = mapped_column(_str_enum(WorkoutOrigin, "workout_origin"))
    garmin_workout_id: Mapped[int | None] = mapped_column(BigInteger)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    user: Mapped[User] = relationship()


class ScheduleStatus(StrEnum):
    PENDING = "pending"  # waiting in the outbox to be sent to Garmin
    SENT = "sent"  # in the Garmin calendar
    FAILED = "failed"  # gave up after too many attempts
    DELETING = "deleting"  # delete requested; removing it from the Garmin calendar
    DELETED = "deleted"


class ScheduledWorkout(Base):
    """Outbox: a workout planned on a day of the Garmin calendar."""

    __tablename__ = "scheduled_workouts"
    __table_args__ = (Index("ix_scheduled_workouts_user_date", "user_id", "date"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    workout_id: Mapped[int] = mapped_column(
        ForeignKey("workouts.id", ondelete="CASCADE"), index=True
    )
    date: Mapped[date] = mapped_column(Date)
    status: Mapped[ScheduleStatus] = mapped_column(_str_enum(ScheduleStatus, "schedule_status"))
    attempts: Mapped[int] = mapped_column(default=0)
    # Short, fixed description of the last failure; never response bodies.
    last_error: Mapped[str | None] = mapped_column(String(200))
    garmin_schedule_id: Mapped[int | None] = mapped_column(BigInteger)
    next_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )

    workout: Mapped[Workout] = relationship()
