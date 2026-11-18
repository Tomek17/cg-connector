"""SQLAlchemy ORM models. Schema changes always go with an Alembic migration."""

from datetime import UTC, datetime
from enum import StrEnum

from sqlalchemy import DateTime, Enum, ForeignKey, String, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def utcnow() -> datetime:
    return datetime.now(UTC)


class Base(DeclarativeBase):
    pass


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
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )

    user: Mapped[User] = relationship(back_populates="garmin_credentials")
