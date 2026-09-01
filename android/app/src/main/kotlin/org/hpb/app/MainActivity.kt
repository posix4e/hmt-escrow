package org.hpb.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.security.SecureRandom
import org.hpb.androidcore.DashboardModel
import org.hpb.androidcore.OkRelayClient
import org.hpb.androidcore.WitnessSession
import org.hpb.androidcore.WorkerSession
import org.hpb.engine.hex
import org.hpb.engine.hexBytes
import org.hpb.protocol.ExternalWork

/**
 * The thin Compose shell — every behavior lives in :androidcore (JVM-tested).
 * The identity key doubles as the Nostr key; payouts land at addresses the
 * worker chooses; earnings verify against txids via any wallet the user
 * trusts (on-device CBF wallet integration is the next roadmap step).
 */
class MainActivity : ComponentActivity() {

    /**
     * The worker key lives in the vault, and a key already stored under the old
     * plain-preferences scheme is migrated rather than abandoned — it is the
     * identity every claim and unpaid earning is addressed to.
     */
    private fun identityKey(vault: Vault): ByteArray {
        vault.workerKey()?.let { return it.hexBytes() }
        val legacy = getSharedPreferences("hpb", Context.MODE_PRIVATE).getString("identity_key", null)
        if (legacy != null) {
            vault.storeWorkerKey(legacy)
            return legacy.hexBytes()
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        vault.storeWorkerKey(key.hex())
        return key
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = Vault(this)
        val settings = getSharedPreferences("hpb", Context.MODE_PRIVATE)
        val relayUrl = settings.getString("relay", null) ?: DEFAULT_RELAY
        val toolUrl = settings.getString("cvat_url", null) ?: ""
        val relays = OkRelayClient(listOf(relayUrl))
        val worker = WorkerSession(relays, identityKey(vault))
        val tools = ToolAccess(vault, mapOf(ExternalWork.TOOL_CVAT to toolUrl))
        setContent {
            MaterialTheme {
                AppScaffold(worker, WitnessSession(relays), DashboardModel(relays), tools)
            }
        }
    }

    private companion object {
        const val DEFAULT_RELAY = "ws://10.0.2.2:6969"
    }
}

@Composable
fun AppScaffold(
    worker: WorkerSession,
    witness: WitnessSession,
    dashboard: DashboardModel,
    tools: ToolAccess? = null,
) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Jobs", "Earnings", "Witness", "Dashboard")
    Scaffold(
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(title.take(1)) },
                        label = { Text(title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when (tab) {
                0 -> JobsScreen(worker, tools)
                1 -> EarningsScreen(worker)
                2 -> WitnessScreen(witness)
                else -> DashboardScreen(dashboard)
            }
        }
    }
}
