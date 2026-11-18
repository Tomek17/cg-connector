"""Alembic environment.

The app runs migrations through `cg_server.db.upgrade_db`, which passes an open connection.
For developer use (`uv run alembic revision --autogenerate -m ...`) the database is taken
from settings (CG_DATA_DIR).
"""

from alembic import context
from sqlalchemy import Connection

from cg_server.config import get_settings
from cg_server.db import create_db_engine
from cg_server.models import Base


def run_migrations(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=Base.metadata, render_as_batch=True)
    with context.begin_transaction():
        context.run_migrations()


connection = context.config.attributes.get("connection")
if context.is_offline_mode():
    raise SystemExit("Tryb offline Alembica nie jest obsługiwany")
if connection is None:
    with create_db_engine(get_settings()).begin() as conn:
        run_migrations(conn)
else:
    run_migrations(connection)
