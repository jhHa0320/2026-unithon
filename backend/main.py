from fastapi import Depends, FastAPI

from backend.core.errors import register_exception_handlers
from backend.core.logging import setup_logging
from backend.routers.decide import get_ai_client
from backend.routers.decide import router as decide_router
from backend.routers.transcribe import router as transcribe_router
from backend.services.ai_client import AIClient

setup_logging()

app = FastAPI(title="PathPilot Backend")

register_exception_handlers(app)
app.include_router(decide_router)
app.include_router(transcribe_router)


@app.get("/health")
def health(ai_client: AIClient = Depends(get_ai_client)) -> dict[str, str]:
    """서버 상태와 **지금 실제로 쓰고 있는 AI 클라이언트**를 함께 알려준다.

    `ai_client`를 노출하는 이유: GEMINI_API_KEY가 비어 있으면 서버가 조용히 MockAIClient로
    폴백하는데(로그 warning 한 줄뿐), Mock은 첫 clickable 요소를 무조건 누른다. 시연 중에
    "왜 엉뚱한 버튼을 누르지"를 그 자리에서 디버깅하는 대신, 시작 전에
    `curl localhost:8000/health`로 `"ai_client":"gemini"`를 눈으로 확인하면 된다.
    """
    return {"status": "ok", "ai_client": getattr(ai_client, "PROVIDER", "unknown")}
