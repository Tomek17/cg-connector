"""Workout sports "other" and "bouldering" (described in words, sent as Garmin "other").

SQLite cannot change a CHECK constraint, so the batch operation rebuilds `workouts`
(new table, copy, DROP old). With foreign keys on, that DROP deletes every row of
`scheduled_workouts` through ON DELETE CASCADE, so those rows are kept in a temporary
table and put back after the rebuild (the first version of this migration lost them;
`cg-server restore-scheduled` brings them back from a backup).

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


def _rebuild_workouts(old: tuple[str, ...], new: tuple[str, ...]) -> None:
    op.execute("DROP TABLE IF EXISTS _scheduled_keep")
    op.execute("CREATE TEMP TABLE _scheduled_keep AS SELECT * FROM scheduled_workouts")
    with op.batch_alter_table("workouts", schema=None) as batch_op:
        batch_op.alter_column("sport", existing_type=_sport(*old), type_=_sport(*new))
    op.execute(
        "INSERT OR IGNORE INTO scheduled_workouts SELECT * FROM _scheduled_keep"
        " WHERE workout_id IN (SELECT id FROM workouts)"
    )
    op.execute("DROP TABLE _scheduled_keep")


def upgrade() -> None:
    _rebuild_workouts(OLD, NEW)


def downgrade() -> None:
    op.execute("DELETE FROM workouts WHERE sport IN ('other', 'bouldering')")
    _rebuild_workouts(NEW, OLD)
