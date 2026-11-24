"""User accounts (one per person using CG-connector)."""

import re

from sqlalchemy import select
from sqlalchemy.orm import Session

from cg_server.config import Settings
from cg_server.models import User

NAME_PATTERN = re.compile(r"[a-z0-9][a-z0-9_-]{0,31}")


class UserError(Exception):
    """Invalid, duplicate or unknown user. The message is shown to the operator."""


def add_user(session: Session, name: str) -> User:
    if not NAME_PATTERN.fullmatch(name):
        raise UserError(
            "Nieprawidłowa nazwa użytkownika: dozwolone małe litery, cyfry, '-' i '_' "
            "(maks. 32 znaki)."
        )
    if get_user(session, name) is not None:
        raise UserError(f"Użytkownik „{name}” już istnieje.")
    user = User(name=name)
    session.add(user)
    session.commit()
    return user


def get_user(session: Session, name: str) -> User | None:
    return session.scalars(select(User).where(User.name == name)).one_or_none()


def is_admin(session: Session, settings: Settings, user: User) -> bool:
    """The admin invites new people from the app: `CG_ADMIN_USER`, else the oldest account."""
    if settings.admin_user:
        return user.name == settings.admin_user
    first = session.scalars(select(User.id).order_by(User.id).limit(1)).first()
    return first == user.id
