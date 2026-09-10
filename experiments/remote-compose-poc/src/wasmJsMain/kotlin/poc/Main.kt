@file:OptIn(
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
)

package poc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import kotlin.io.encoding.Base64
import kotlinx.coroutines.delay

@JsFun("() => window.pocPayload || ''") external fun payload(): String

@JsFun(
  "(message) => { const log = document.getElementById('events'); log.textContent = message + '\\n' + log.textContent; }"
)
external fun logEvent(message: String)

fun main() {
  ComposeViewport(viewportContainerId = "composeApp") {
    MaterialTheme {
      var encoded by remember { mutableStateOf("") }
      LaunchedEffect(Unit) {
        while (true) {
          val next = payload()
          if (next != encoded) encoded = next
          delay(100)
        }
      }
      if (encoded.isBlank()) Text("Compile an interface to start the real player.")
      else
        key(encoded) {
          val bytes = remember(encoded) { Base64.decode(encoded) }
          Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
              Text("Remote document · interactive", style = MaterialTheme.typography.titleMedium)
              Spacer(Modifier.height(12.dp))
              RcComposePlayer(
                bytes,
                Modifier.size(320.dp, 260.dp),
                onEvent = { logEvent("Remote: $it") },
              )
            }
            Column {
              Text(
                "Compiled Compose export · baseline",
                style = MaterialTheme.typography.titleMedium,
              )
              Spacer(Modifier.height(12.dp))
              GeneratedInterface(onHostAction = { logEvent("Compose: $it") })
            }
          }
        }
    }
  }
}
