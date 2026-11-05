// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.coffer.ffi.FfiCancel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nativeOk = runCatching { FfiCancel().close() }
            .onFailure { Log.e("Coffer", "native library failed to load", it) }
            .isSuccess
        Log.i("Coffer", "native library loaded: $nativeOk")
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.tagline))
                        Text(stringResource(if (nativeOk) R.string.native_ok else R.string.native_failed))
                    }
                }
            }
        }
    }
}
