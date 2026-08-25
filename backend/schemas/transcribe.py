from pydantic import BaseModel


class TranscribeResponse(BaseModel):
    """POST /api/v1/transcribe 응답 바디.

    받아쓴 문장 하나만 돌려준다. 클라이언트는 이 값을 기존 온디바이스 STT 결과가 들어가던
    자리(goal 또는 user_speech)에 그대로 넣으면 된다 — 그래야 STT 제공자를 바꾸는 실험이
    나머지 파이프라인을 건드리지 않는다.

    인식하지 못했으면 빈 문자열이다(에러가 아니다). 호출부가 빈 값을 "못 알아들었음"으로
    처리해야 한다 — 빈 문자열을 목표로 삼으면 자동화가 엉뚱하게 진행된다.
    """

    text: str
