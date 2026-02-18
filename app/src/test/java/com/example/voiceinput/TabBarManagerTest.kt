package com.example.voiceinput

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TabBarManagerTest {

    private lateinit var manager: TabBarManager
    private val contentShown = mutableListOf<Int>()

    @Before
    fun setUp() {
        contentShown.clear()
        manager = TabBarManager { tab -> contentShown.add(tab) }
    }

    @Test
    fun `initial tab is VOICE`() {
        assertEquals(TabBarManager.TAB_VOICE, manager.currentTab)
    }

    @Test
    fun `selectTab changes currentTab`() {
        manager.selectTab(TabBarManager.TAB_COMMAND)
        assertEquals(TabBarManager.TAB_COMMAND, manager.currentTab)
    }

    @Test
    fun `selectTab notifies content change`() {
        manager.selectTab(TabBarManager.TAB_TMUX)
        assertEquals(listOf(TabBarManager.TAB_TMUX), contentShown)
    }

    @Test
    fun `selectTab same tab does not notify`() {
        // Initial tab is VOICE, selecting VOICE again should be no-op
        manager.selectTab(TabBarManager.TAB_VOICE)
        assertTrue(contentShown.isEmpty())
    }

    @Test
    fun `getTabStyle returns selected style for current tab`() {
        manager.selectTab(TabBarManager.TAB_COMMAND)
        val style = manager.getTabStyle(TabBarManager.TAB_COMMAND)
        assertTrue(style.isSelected)
        assertEquals(1f, style.elevation)
        assertEquals(2f, style.translationY)
        assertEquals(0xFFE0E6ED.toInt(), style.textColor)
    }

    @Test
    fun `getTabStyle returns unselected style for non-current tab`() {
        manager.selectTab(TabBarManager.TAB_COMMAND)
        val style = manager.getTabStyle(TabBarManager.TAB_VOICE)
        assertFalse(style.isSelected)
        assertEquals(6f, style.elevation)
        assertEquals(0f, style.translationY)
        assertEquals(0xFF8B949E.toInt(), style.textColor)
    }

    @Test
    fun `animation duration is 90ms`() {
        assertEquals(90L, TabBarManager.ANIM_DURATION_MS)
    }

    @Test
    fun `flash duration is 20ms`() {
        assertEquals(20L, TabBarManager.FLASH_DURATION_MS)
    }

    @Test
    fun `tab constants are 0 1 2`() {
        assertEquals(0, TabBarManager.TAB_VOICE)
        assertEquals(1, TabBarManager.TAB_COMMAND)
        assertEquals(2, TabBarManager.TAB_TMUX)
    }

    @Test
    fun `sequential tab changes track correctly`() {
        manager.selectTab(TabBarManager.TAB_TMUX)
        manager.selectTab(TabBarManager.TAB_COMMAND)
        manager.selectTab(TabBarManager.TAB_VOICE)
        assertEquals(TabBarManager.TAB_VOICE, manager.currentTab)
        assertEquals(
            listOf(TabBarManager.TAB_TMUX, TabBarManager.TAB_COMMAND, TabBarManager.TAB_VOICE),
            contentShown
        )
    }
}
