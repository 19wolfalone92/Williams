package com.williamsbot.domain.preferences

interface UserPreferencesStore {
    val autoRun: Boolean
    val maxOpenPositions: Int

    fun setAutoRun(enabled: Boolean): Boolean
}
