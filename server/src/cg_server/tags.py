"""Activity tags for the Progress tab: one tag per activity, from a fixed list per type.

Set by Claude (MCP `set_activity_tag`) or in the app (`PUT /api/v1/activities/{id}/tag`).
Keys are stable English words; the app shows Polish names.
"""

from sqlalchemy.orm import Session

from cg_server import queries
from cg_server.models import Activity, ActivityKind

TAGS: dict[str, ActivityKind] = {
    "intervals": ActivityKind.RUNNING,
    "tempo": ActivityKind.RUNNING,
    "long": ActivityKind.RUNNING,
    "base": ActivityKind.RUNNING,
    "easy": ActivityKind.RUNNING,
    "strides": ActivityKind.RUNNING,
    "upper": ActivityKind.STRENGTH,
    "fbw": ActivityKind.STRENGTH,
    "push": ActivityKind.STRENGTH,
    "pull": ActivityKind.STRENGTH,
    "legs": ActivityKind.STRENGTH,
    "wall": ActivityKind.CLIMBING,
}


class TagError(ValueError):
    """Unknown tag, tag for another activity type, or no such activity."""


def tags_for(kind: ActivityKind) -> list[str]:
    return [tag for tag, tag_kind in TAGS.items() if tag_kind == kind]


def set_tag(session: Session, *, user_id: int, activity_id: int, tag: str | None) -> Activity:
    """Set (or with None clear) the tag of the user's activity, by its Garmin id."""
    activity = queries.get_activity(session, user_id=user_id, activity_id=activity_id)
    if activity is None:
        raise TagError(f"No activity {activity_id}.")
    if tag is not None:
        tag = tag.strip().lower()
        kind = TAGS.get(tag)
        if kind is None:
            raise TagError(f"Unknown tag '{tag}'. Tags: {', '.join(TAGS)}.")
        if kind != activity.kind:
            allowed = ", ".join(tags_for(activity.kind)) or "none"
            raise TagError(
                f"Tag '{tag}' is for {kind.value}; this activity is {activity.kind.value}"
                f" (allowed: {allowed})."
            )
    activity.tag = tag
    session.commit()
    return activity
