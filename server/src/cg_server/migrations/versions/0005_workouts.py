"""Workouts and the outbox of scheduled workouts.

Revision ID: 0005
Revises: 0004
Create Date: 2026-11-18
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _enum(name: str, *values: str) -> sa.Enum:
    return sa.Enum(*values, name=name, native_enum=False, create_constraint=True, length=16)


def upgrade() -> None:
    op.create_table(
        "workouts",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("client_id", sa.String(length=36), nullable=False),
        sa.Column("name", sa.String(length=60), nullable=False),
        sa.Column("sport", _enum("workout_sport", "running", "strength"), nullable=False),
        sa.Column("definition_json", sa.JSON(), nullable=False),
        sa.Column("created_by", _enum("workout_origin", "app", "mcp"), nullable=False),
        sa.Column("garmin_workout_id", sa.BigInteger(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("user_id", "client_id"),
    )
    with op.batch_alter_table("workouts", schema=None) as batch_op:
        batch_op.create_index(batch_op.f("ix_workouts_user_id"), ["user_id"], unique=False)

    op.create_table(
        "scheduled_workouts",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("workout_id", sa.Integer(), nullable=False),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column(
            "status",
            _enum("schedule_status", "pending", "sent", "failed", "deleting", "deleted"),
            nullable=False,
        ),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("last_error", sa.String(length=200), nullable=True),
        sa.Column("garmin_schedule_id", sa.BigInteger(), nullable=True),
        sa.Column("next_attempt_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["workout_id"], ["workouts.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    with op.batch_alter_table("scheduled_workouts", schema=None) as batch_op:
        batch_op.create_index("ix_scheduled_workouts_user_date", ["user_id", "date"], unique=False)
        batch_op.create_index(
            batch_op.f("ix_scheduled_workouts_workout_id"), ["workout_id"], unique=False
        )


def downgrade() -> None:
    with op.batch_alter_table("scheduled_workouts", schema=None) as batch_op:
        batch_op.drop_index(batch_op.f("ix_scheduled_workouts_workout_id"))
        batch_op.drop_index("ix_scheduled_workouts_user_date")
    op.drop_table("scheduled_workouts")
    with op.batch_alter_table("workouts", schema=None) as batch_op:
        batch_op.drop_index(batch_op.f("ix_workouts_user_id"))
    op.drop_table("workouts")
