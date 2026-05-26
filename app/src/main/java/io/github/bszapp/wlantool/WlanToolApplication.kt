package io.github.bszapp.wlantool

import android.app.Application
import io.github.bszapp.wlantool.terminal.TerminalSessionManager

class WlanToolApplication : Application() {
    val terminalSessionManager: TerminalSessionManager by lazy {
        TerminalSessionManager(this)
    }
}
