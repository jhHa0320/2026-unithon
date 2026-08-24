from fastapi import FastAPI

from backend.core.errors import register_exception_handlers
from backend.core.logging import setup_logging
from backend.routers.decide import router as decide_router

setup_logging()

app = FastAPI(title="PathPilot Backend")

register_exception_handlers(app)
app.include_router(decide_router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
