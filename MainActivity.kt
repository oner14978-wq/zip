package com.mclauncher.app

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Registro oficial de plugin local no Capacitor: ANTES do super.onCreate.
        registerPlugin(MCNativePlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
