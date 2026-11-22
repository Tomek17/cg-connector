"""Workout sports "other" and "bouldering" (described in words, sent as Garmin "other").

Revision ID: 0006
Revises: 0005
Create Date: 2026-11-22
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _sport(*values: str) -> sa.Enum:
    return sa.Enum(
        *values, name="workout_sport", native_enum=False, create_constraint=True, length=16
    )


OLD = ("running", "strength")
NEW = (*OLD, "other", "bouldering")


def upgrade() -> None:
    with op.batch_alter_table("workouts", schema=None) as batch_op:
        batch_op.alter_column("sport", existing_type=_sport(*OLD), type_=_sport(*NEW))


def downgrade() -> None:
    op.execute("DELETE FROM workouts WHERE sport IN ('other', 'bouldering')")
    with op.batch_alter_table("workouts", schema=None) as batch_op:
        batch_op.alter_column("sport", existing_type=_sport(*NEW), type_=_sport(*OLD))
