"""개발용 하네스 라우터 — 프로덕션 기능이 아니다.

브라우저에서 에이전트 루프를 눈으로 보면서 프롬프트를 튜닝하기 위한 도구.
백엔드와 같은 origin에서 서빙하므로 CORS 설정이 필요 없다.
"""

from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter(tags=["dev"])

HARNESS_PATH = Path(__file__).resolve().parents[1] / "dev" / "harness.html"


@router.get("/dev", response_class=HTMLResponse)
def harness() -> HTMLResponse:
    return HTMLResponse(HARNESS_PATH.read_text(encoding="utf-8"))
