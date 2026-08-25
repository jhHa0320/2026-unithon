"""LLM 연결 자가진단 — 키를 넣은 사람이 30초 안에 되는지 확인하는 스크립트.

    python -m backend.dev.check_llm

실제로 한 번 호출해서 앱 선택 판단을 받아본다. 실패하면 무엇이 문제인지 그대로 찍는다.
"""

import sys
import traceback

from backend.config import get_settings
from backend.routers.decide import get_ai_client
from backend.schemas.request import InstalledApp
from backend.services.ai_client import GeminiAIClient, MockAIClient


def main() -> int:
    settings = get_settings()
    client = get_ai_client()
    name = type(client).__name__

    print(f"프로바이더 : {name}")
    if isinstance(client, GeminiAIClient):
        print(f"모델       : {settings.GEMINI_MODEL}")
    if isinstance(client, MockAIClient):
        print()
        print("키가 설정되어 있지 않아 Mock으로 동작한다.")
        print(".env에 GEMINI_API_KEY=... 를 넣고 다시 실행할 것.")
        return 1

    print("호출 중...")
    try:
        result = client.decide(
            goal="카톡으로 영희한테 사진 보내줘",
            app_package=None,
            elements=[],
            installed_apps=[
                InstalledApp(package="com.kakao.talk", label="카카오톡"),
                InstalledApp(package="com.nhn.android.search", label="네이버"),
            ],
            history=[],
        )
    except Exception:
        print("\n실패 —")
        traceback.print_exc()
        print("\n자주 나오는 원인:")
        print("  - 키가 잘못됨 / 권한 없음        → 콘솔에서 키 재발급")
        print("  - google-genai 미설치           → pip install -r requirements.txt")
        print("  - 모델 ID가 계정에서 사용 불가   → .env의 GEMINI_MODEL 변경")
        print("  - response_schema 거절(400)     → GEMINI_MODEL을 다른 버전으로 바꿔 재시도")
        return 1

    print("\n성공 —")
    print(f"  action     : {result.action}")
    print(f"  value      : {result.value}")
    print(f"  status     : {result.status}")
    print(f"  confidence : {result.confidence}")
    print(f"  instruction: {result.instruction}")
    if result.action != "LAUNCH_APP":
        print("\n주의: LAUNCH_APP이 나와야 정상이다. 프롬프트를 확인할 것.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
