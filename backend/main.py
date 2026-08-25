from fastapi import FastAPI

from backend.core.errors import register_exception_handlers
from backend.core.logging import setup_logging
from backend.routers.decide import router as decide_router
from backend.routers.dev import router as dev_router

setup_logging()

app = FastAPI(title="PathPilot Backend")

register_exception_handlers(app)
app.include_router(decide_router)
app.include_router(dev_router)  # 개발용 하네스 (GET /dev)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
