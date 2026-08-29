package org.hpb.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hpb.androidcore.DashboardModel
import org.hpb.androidcore.WitnessSession
import org.hpb.androidcore.WorkerSession
import org.hpb.protocol.Answer
import org.hpb.protocol.AssignmentStatus

@Composable
fun JobsScreen(worker: WorkerSession) {
    var jobs by remember { mutableStateOf<List<WorkerSession.JobRow>>(emptyList()) }
    var payoutAddress by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        jobs = withContext(Dispatchers.IO) { runCatching { worker.openJobs() }.getOrDefault(emptyList()) }
    }
    OutlinedTextField(
        value = payoutAddress,
        onValueChange = { payoutAddress = it },
        label = { Text("Your payout address") },
    )
    Spacer(Modifier.height(8.dp))
    Text(status)
    LazyColumn {
        items(jobs) { job ->
            Text("${job.offer.jobType} — ${job.offer.rewardPerTaskSats} sats/task, ${job.offer.tasks.size} tasks")
            TextButton(onClick = {
                status = runCatching { worker.claim(job, payoutAddress, emptyList()) }
                    .fold({ "claimed ${job.offer.escrowId.take(8)}…" }, { "claim failed: ${it.message}" })
            }) { Text("Claim") }
            AssignmentPanel(worker, job)
            HorizontalDivider()
        }
    }
}

@Composable
private fun AssignmentPanel(worker: WorkerSession, job: WorkerSession.JobRow) {
    var answers by remember { mutableStateOf(mapOf<String, String>()) }
    var state by remember { mutableStateOf("") }
    Button(onClick = {
        state = runCatching {
            val mine = worker.assignments(job).firstOrNull()
            when (mine?.status) {
                AssignmentStatus.ACTIVE -> {
                    worker.submit(job, mine, job.offer.tasks.map { Answer(it.key, answers[it.key].orEmpty()) })
                    "submitted"
                }
                null -> "no assignment yet"
                else -> mine.status.name.lowercase()
            }
        }.getOrElse { "error: ${it.message}" }
    }) { Text("Refresh / Submit") }
    Text(state)
    job.offer.tasks.forEach { task ->
        OutlinedTextField(
            value = answers[task.key].orEmpty(),
            onValueChange = { answers = answers + (task.key to it) },
            label = { Text(task.question) },
        )
    }
}

@Composable
fun EarningsScreen(worker: WorkerSession) {
    var earnings by remember { mutableStateOf<List<WorkerSession.Earning>>(emptyList()) }
    LaunchedEffect(Unit) {
        earnings = withContext(Dispatchers.IO) {
            runCatching { worker.earnings() }.getOrDefault(emptyList())
        }
    }
    Text("Total: ${earnings.sumOf { it.sats }} sats")
    LazyColumn {
        items(earnings) { earning ->
            Text("${earning.sats} sats — tx ${earning.txid.take(16)}…")
        }
    }
}

@Composable
fun WitnessScreen(witness: WitnessSession) {
    var escrowId by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    OutlinedTextField(
        value = escrowId,
        onValueChange = { escrowId = it },
        label = { Text("Escrow id (co-sign request)") },
    )
    Button(onClick = {
        summary = runCatching {
            val s = witness.summarize(escrowId.trim())
            "Launcher ${s.launcher.take(12)}…\n" +
                s.expectedLines.joinToString("\n") { "${it.address}  ${it.sats} sats" } +
                "\nresults ${s.resultsHashHex.take(16)}…" +
                "\n\nSign ONLY a PSBT paying exactly these outputs."
        }.getOrElse { "verification failed: ${it.message}" }
    }) { Text("Verify") }
    Text(summary)
}

@Composable
fun DashboardScreen(dashboard: DashboardModel) {
    var snapshot by remember { mutableStateOf<DashboardModel.Snapshot?>(null) }
    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) { runCatching { dashboard.snapshot() }.getOrNull() }
    }
    Column {
        val s = snapshot
        if (s == null) {
            Text("Loading…")
        } else {
            Text("Open jobs: ${s.openJobs}")
            Text("Open reward pool: ${s.totalRewardPoolSats} sats")
            Text("Payouts: ${s.payouts}")
            Text("Sats paid: ${s.satsPaid}")
            Text("Workers paid: ${s.workersPaid}")
        }
    }
}
