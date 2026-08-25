package com.example.pathpilot.testkit

/**
 * 테스트용 프로세스 내 공유 상태 — [com.example.pathpilot.MainActivity]에서 사용자가
 * 자연어로 입력한 목표를 [TestAccessibilityService]로 넘기는 용도.
 *
 * 정식 구현이 아니다. SharedPreferences/Intent extra 등 제대로 된 전달 방식 대신 프로세스가
 * 살아있는 동안만 유효한 최소 홀더를 쓴다 — MainActivity가 카톡을 켜자마자 같은 프로세스의
 * AccessibilityService가 곧바로 읽어가는 짧은 수명이라 충분하다.
 */
object GoalHolder {
    @Volatile
    private var pendingGoal: String? = null

    fun set(goal: String) {
        pendingGoal = goal
    }

    /** 한 번 읽으면 비운다 — 다음 세션(카톡 재진입)에서 같은 목표가 재사용되지 않도록. */
    fun consume(): String? {
        val goal = pendingGoal
        pendingGoal = null
        return goal
    }
}
