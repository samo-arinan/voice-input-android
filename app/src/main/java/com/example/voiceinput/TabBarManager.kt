package com.example.voiceinput

class TabBarManager(private val onContentChange: (Int) -> Unit) {

    companion object {
        const val TAB_VOICE = 0
        const val TAB_COMMAND = 1
        const val TAB_TMUX = 2
        const val ANIM_DURATION_MS = 90L
        const val FLASH_DURATION_MS = 20L
    }

    var currentTab: Int = TAB_VOICE
        private set

    data class TabStyle(
        val isSelected: Boolean,
        val elevation: Float,
        val translationY: Float,
        val textColor: Int
    )

    fun selectTab(tab: Int) {
        if (tab == currentTab) return
        currentTab = tab
        onContentChange(tab)
    }

    fun getTabStyle(tab: Int): TabStyle {
        return if (tab == currentTab) {
            TabStyle(
                isSelected = true,
                elevation = 1f,
                translationY = 2f,
                textColor = 0xFFE0E6ED.toInt()
            )
        } else {
            TabStyle(
                isSelected = false,
                elevation = 6f,
                translationY = 0f,
                textColor = 0xFF8B949E.toInt()
            )
        }
    }
}
