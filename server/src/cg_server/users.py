"""User accounts (one per person using CG-connector)."""

import re

from sqlalchemy import select
from sqlalchemy.orm import Session

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
