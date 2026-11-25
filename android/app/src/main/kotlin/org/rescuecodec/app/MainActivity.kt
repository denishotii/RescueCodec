package org.rescuecodec.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.meshtastic.sdk.RadioClient
import org.rescuecodec.engine.Engine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Trivial SDK usage: proves the Meshtastic SDK resolves and compiles.
        val sdkType = RadioClient.Builder::class.java.simpleName
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("RescueCodec", style = MaterialTheme.typography.headlineMedium)
                        Text(Engine.describe())
                        Text("Meshtastic SDK: $sdkType")
                    }
                }
            }
        }
    }
}
