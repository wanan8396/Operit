package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserOverlayWindowFlagsTest {
    @Test
    fun expandedWindowKeepsHardwareAccelerationAndAcceptsInput() {
        val expected = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        assertEquals(expected, browserOverlayWindowFlags(expanded = true))
    }

    @Test
    fun minimizedWindowKeepsHardwareAccelerationWithoutInterceptingInput() {
        val expected = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        assertEquals(expected, browserOverlayWindowFlags(expanded = false))
    }
}
