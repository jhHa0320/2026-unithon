package com.example.pathpilot.settings

import android.content.Context

/**
 * "안녕 손자"에서 "손자" 부분을 사용자가 바꿀 수 있게 하는 설정 저장소.
 * DB 도입 전까지는 SharedPreferences로 충분 — 값 하나짜리 로컬 설정이라 별도 스키마가 필요 없다.
 * (DB로 옮길지 여부는 팀에서 추가 논의)
 */
object WakeWordSettings {
    const val DEFAULT_NAME = "손자"

    private const val PREFS_NAME = "wake_word_settings"
    private const val KEY_NAME = "wake_word_name"

    fun getName(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_NAME, DEFAULT_NAME)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
    }

    fun setName(context: Context, name: String) {
        val trimmed = name.trim()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_NAME, trimmed.ifBlank { DEFAULT_NAME }).apply()
    }

    /** "안녕 {이름}" 전체 웨이크 문구. */
    fun getWakePhrase(context: Context): String = "안녕 ${getName(context)}"
}
