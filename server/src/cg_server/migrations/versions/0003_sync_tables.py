"""Sync tables: activities, activity_details, vo2max, sync_runs.

Revision ID: 0003
Revises: 0002
Create Date: 2026-11-18
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "activities",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("garmin_activity_id", sa.BigInteger(), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=True),
        sa.Column(
            "kind",
            sa.Enum(
                "running",
                "strength",
                "climbing",
                "other",
                name="activity_kind",
                native_enum=False,
                create_constraint=True,
                length=16,
            ),
            nullable=False,
        ),
        sa.Column("type_key", sa.String(length=64), nullable=False),
        sa.Column("start_time_utc", sa.DateTime(timezone=True), nullable=False),
        sa.Column("start_time_local", sa.DateTime(), nullable=False),
        sa.Column("duration_s", sa.Float(), nullable=True),
        sa.Column("moving_duration_s", sa.Float(), nullable=True),
        sa.Column("distance_m", sa.Float(), nullable=True),
        sa.Column("avg_hr", sa.Float(), nullable=True),
        sa.Column("max_hr", sa.Float(), nullable=True),
        sa.Column("avg_speed_mps", sa.Float(), nullable=True),
        sa.Column("avg_pace_s_per_km", sa.Float(), nullable=True),
        sa.Column("elevation_gain_m", sa.Float(), nullable=True),
        sa.Column("avg_power_w", sa.Float(), nullable=True),
        sa.Column("avg_cadence_spm", sa.Float(), nullable=True),
        sa.Column("aerobic_training_effect", sa.Float(), nullable=True),
        sa.Column("anaerobic_training_effect", sa.Float(), nullable=True),
        sa.Column("training_load", sa.Float(), nullable=True),
        sa.Column("summary_json", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("user_id", "garmin_activity_id"),
    )
    with op.batch_alter_table("activities", schema=None) as batch_op:
        batch_op.create_index(
            "ix_activities_user_start", ["user_id", "start_time_utc"], unique=False
        )

    op.create_table(
        "sync_runs",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "status",
            sa.Enum(
                "running",
                "ok",
                "partial",
                "failed",
                "skipped_rate_limited",
                name="sync_status",
                native_enum=False,
                create_constraint=True,
                length=24,
            ),
            nullable=False,
        ),
        sa.Column("cursor_date", sa.Date(), nullable=True),
        sa.Column("activities_new", sa.Integer(), nullable=False),
        sa.Column("activities_updated", sa.Integer(), nullable=False),
        sa.Column("request_limit_reached", sa.Boolean(), nullable=False),
        sa.Column("error", sa.String(length=200), nullable=True),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    with op.batch_alter_table("sync_runs", schema=None) as batch_op:
        batch_op.create_index("ix_sync_runs_user_started", ["user_id", "started_at"], unique=False)

    op.create_table(
        "vo2max",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("sport", sa.String(length=16), nullable=False),
        sa.Column("value", sa.Float(), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("user_id", "date", "sport"),
    )
    op.create_table(
        "activity_details",
        sa.Column("activity_id", sa.Integer(), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("laps_json", sa.JSON(), nullable=True),
        sa.Column("hr_zones_json", sa.JSON(), nullable=True),
        sa.Column("exercise_sets_json", sa.JSON(), nullable=True),
        sa.Column("fetch_failed", sa.Boolean(), nullable=False),
        sa.Column("fetched_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["activity_id"], ["activities.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("activity_id"),
    )
    with op.batch_alter_table("activity_details", schema=None) as batch_op:
        batch_op.create_index(batch_op.f("ix_activity_details_user_id"), ["user_id"], unique=False)


def downgrade() -> None:
    with op.batch_alter_table("activity_details", schema=None) as batch_op:
        batch_op.drop_index(batch_op.f("ix_activity_details_user_id"))

    op.drop_table("activity_details")
    op.drop_table("vo2max")
    with op.batch_alter_table("sync_runs", schema=None) as batch_op:
        batch_op.drop_index("ix_sync_runs_user_started")

    op.drop_table("sync_runs")
    with op.batch_alter_table("activities", schema=None) as batch_op:
        batch_op.drop_index("ix_activities_user_start")

    op.drop_table("activities")
