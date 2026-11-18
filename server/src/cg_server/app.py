"""FastAPI application factory."""

from fastapi import FastAPI

from cg_server import __version__


def create_app() -> FastAPI:
    app = FastAPI(
        title="CG-connector",
        version=__version__,
        docs_url=None,  # no public admin/docs UI (master prompt 3.3)
        redoc_url=None,
    )

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok", "version": __version__}

    return app


app = create_app()
