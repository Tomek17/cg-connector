"""REST API for the Android app (`/api/v1`). Auth: `Authorization: Bearer <app token>`."""

from collections.abc import Iterator
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel
from sqlalchemy.orm import Session, sessionmaker

from cg_server.models import User
from cg_server.user_secrets import SecretKind, user_for_secret

bearer = HTTPBearer(auto_error=False)


def db_session(request: Request) -> Iterator[Session]:
    sessions: sessionmaker[Session] = request.app.state.sessions
    with sessions() as session:
        yield session


def current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    session: Annotated[Session, Depends(db_session)],
) -> User:
    """The user owning the app token. Every data query must filter by this user."""
    user = None
    if credentials is not None:
        user = user_for_secret(session, SecretKind.APP_TOKEN, credentials.credentials)
    if user is None:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            detail="Nieprawidłowy token aplikacji",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return user


CurrentUser = Annotated[User, Depends(current_user)]


class Me(BaseModel):
    name: str
    garmin_status: str | None


router = APIRouter(prefix="/api/v1")


@router.get("/me")
def me(user: CurrentUser) -> Me:
    creds = user.garmin_credentials
    return Me(name=user.name, garmin_status=creds.status.value if creds else None)
