"""activities.tag: one tag per activity (e.g. intervals, legs, wall) for the Progress tab.

Plain ADD/DROP COLUMN, never a batch rebuild: rebuilding `activities` would delete
`activity_details` through ON DELETE CASCADE (see 0006).

Revision ID: 0007
Revises: 0006
Create Date: 2026-11-22
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("activities", sa.Column("tag", sa.String(length=16), nullable=True))


def downgrade() -> None:
    op.execute("ALTER TABLE activities DROP COLUMN tag")
