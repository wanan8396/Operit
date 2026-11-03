package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebSessionWebViewHostAndroidTest {
    private fun withHost(test: (WebSessionWebViewHost, WebView, FrameLayout) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val webView = WebView(context)
            val container = FrameLayout(context)
            val host = WebSessionWebViewHost()
            try {
                host.setActiveWebView(webView, null, null)
                host.attachContainer(container)
                test(host, webView, container)
            } finally {
                host.clear()
                webView.destroy()
            }
        }
    }

    @Test
    fun repeatedComposeUpdatesDoNotReparentWebView() = withHost { host, webView, container ->
        var added = 0
        var removed = 0
        container.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) { added++ }
            override fun onChildViewRemoved(parent: View?, child: View?) { removed++ }
        })
        repeat(10) {
            host.attachContainer(container)
            host.setActiveWebView(webView, null, null)
        }
        assertSame(container, webView.parent)
        assertEquals(0, added)
        assertEquals(0, removed)
    }

    @Test
    fun releaseDetachesWithoutDestroyingSessionWebView() = withHost { host, webView, container ->
        host.detachContainer(container)
        assertNull(webView.parent)
        assertSame(webView, host.currentWebView())
        host.attachContainer(container)
        assertSame(container, webView.parent)
    }

    @Test
    fun releasingOldContainerDoesNotDetachReplacement() = withHost { host, webView, oldContainer ->
        val replacement = FrameLayout(oldContainer.context)
        host.attachContainer(replacement)
        host.detachContainer(oldContainer)
        assertSame(replacement, webView.parent)
        assertEquals(1, replacement.childCount)
        host.detachContainer(replacement)
        assertNull(webView.parent)
    }

    @Test
    fun clearingViewportOverrideRestoresPhoneLayout() = withHost { host, webView, container ->
        container.layout(0, 0, 1080, 1600)
        host.setViewportSize(1280, 720)
        val density = webView.resources.displayMetrics.density
        assertEquals(kotlin.math.round(1280 * density).toInt(), webView.layoutParams.width)
        host.setViewportSize(null, null)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.height)
        assertEquals(1f, webView.scaleX, 0f)
        assertEquals(1f, webView.scaleY, 0f)
        assertEquals(0f, webView.translationX, 0f)
        assertEquals(0f, webView.translationY, 0f)
    }
}
