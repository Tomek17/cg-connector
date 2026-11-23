"""activity_details.typed_splits_json: Garmin typed splits (climbing: each boulder/route).

Plain ADD/DROP COLUMN, never a batch rebuild (see 0006 and 0007).

Revision ID: 0009
Revises: 0008
Create Date: 2026-11-23
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0009"
down_revision: str | None = "0008"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("activity_details", sa.Column("typed_splits_json", sa.JSON(), nullable=True))


def downgrade() -> None:
    op.execute("ALTER TABLE activity_details DROP COLUMN typed_splits_json")
