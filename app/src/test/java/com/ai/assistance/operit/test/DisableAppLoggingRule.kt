package com.ai.assistance.operit.test

import com.ai.assistance.operit.util.AppLogger
import org.junit.rules.ExternalResource

/** JVM tests have no Android Log implementation; restore shared flags after each test. */
class DisableAppLoggingRule : ExternalResource() {
    private var previousSystemLogEnabled = true
    private var previousFileLogEnabled = true

    override fun before() {
        previousSystemLogEnabled = AppLogger.enableSystemLog
        previousFileLogEnabled = AppLogger.enableFileLogging
        AppLogger.enableSystemLog = false
        AppLogger.enableFileLogging = false
    }

    override fun after() {
        AppLogger.enableSystemLog = previousSystemLogEnabled
        AppLogger.enableFileLogging = previousFileLogEnabled
    }
}
