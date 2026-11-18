"""SQLAlchemy ORM models. Schema changes always go with an Alembic migration."""

from datetime import UTC, date, datetime
from enum import StrEnum
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
    Date,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Index,
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
    """One sync attempt for one user. `cursor_date`: data up to this local date is saved."""

    __tablename__ = "sync_runs"
    __table_args__ = (Index("ix_sync_runs_user_started", "user_id", "started_at"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    status: Mapped[SyncStatus] = mapped_column(_str_enum(SyncStatus, "sync_status", 24))
    cursor_date: Mapped[date | None] = mapped_column(Date)
    activities_new: Mapped[int] = mapped_column(default=0)
    activities_updated: Mapped[int] = mapped_column(default=0)
    # Short, fixed description of the failure; never response bodies or personal data.
    error: Mapped[str | None] = mapped_column(String(200))
