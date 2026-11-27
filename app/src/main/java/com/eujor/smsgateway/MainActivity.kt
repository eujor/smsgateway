package com.eujor.smsgateway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.eujor.smsgateway.ui.GatewayApp
import com.eujor.smsgateway.ui.GatewayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GatewayTheme { GatewayApp() } }
    }
}
