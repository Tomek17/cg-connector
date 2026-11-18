"""SQLAlchemy ORM models. Schema changes always go with an Alembic migration."""

from datetime import UTC, datetime
from enum import StrEnum

from sqlalchemy import Boolean, DateTime, Enum, ForeignKey, Integer, String, Text, UniqueConstraint
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
