"""User secrets (CG code, app token) and OAuth 2.1 state (clients, codes, tokens).

Revision ID: 0004
Revises: 0003
Create Date: 2026-11-18
"""

from collections.abc import Sequence
from datetime import datetime

import sqlalchemy as sa
from alembic import op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _user_fk() -> sa.Column[int]:
    return sa.Column(
        "user_id", sa.Integer(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )


def _client_fk() -> sa.Column[str]:
    return sa.Column(
        "client_id",
        sa.String(64),
        sa.ForeignKey("oauth_clients.client_id", ondelete="CASCADE"),
        nullable=False,
    )


def _created_at() -> sa.Column[datetime]:
    return sa.Column("created_at", sa.DateTime(timezone=True), nullable=False)


def upgrade() -> None:
    op.create_table(
        "user_secrets",
        sa.Column("id", sa.Integer(), primary_key=True),
        _user_fk(),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("secret_hash", sa.String(64), nullable=False, unique=True),
        _created_at(),
        sa.UniqueConstraint("user_id", "kind"),
    )
    op.create_index("ix_user_secrets_user_id", "user_secrets", ["user_id"])
    op.create_table(
        "oauth_clients",
        sa.Column("client_id", sa.String(64), primary_key=True),
        sa.Column("metadata_json", sa.Text(), nullable=False),
        sa.Column("client_secret_encrypted", sa.Text(), nullable=True),
        _created_at(),
    )
    op.create_table(
        "oauth_codes",
        sa.Column("code_hash", sa.String(64), primary_key=True),
        _client_fk(),
        _user_fk(),
        sa.Column("scopes", sa.Text(), nullable=False),
        sa.Column("code_challenge", sa.String(128), nullable=False),
        sa.Column("redirect_uri", sa.Text(), nullable=False),
        sa.Column("redirect_uri_provided_explicitly", sa.Boolean(), nullable=False),
        sa.Column("resource", sa.Text(), nullable=True),
        sa.Column("expires_at", sa.Integer(), nullable=False),
        _created_at(),
    )
    op.create_index("ix_oauth_codes_client_id", "oauth_codes", ["client_id"])
    op.create_index("ix_oauth_codes_user_id", "oauth_codes", ["user_id"])
    op.create_table(
        "oauth_tokens",
        sa.Column("token_hash", sa.String(64), primary_key=True),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("grant_id", sa.String(64), nullable=False),
        _client_fk(),
        _user_fk(),
        sa.Column("scopes", sa.Text(), nullable=False),
        sa.Column("resource", sa.Text(), nullable=True),
        sa.Column("expires_at", sa.Integer(), nullable=False),
        _created_at(),
    )
    op.create_index("ix_oauth_tokens_grant_id", "oauth_tokens", ["grant_id"])
    op.create_index("ix_oauth_tokens_client_id", "oauth_tokens", ["client_id"])
    op.create_index("ix_oauth_tokens_user_id", "oauth_tokens", ["user_id"])


def downgrade() -> None:
    op.drop_table("oauth_tokens")
    op.drop_table("oauth_codes")
    op.drop_table("oauth_clients")
    op.drop_table("user_secrets")
