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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hpb.androidcore.DashboardModel
import org.hpb.androidcore.WitnessSession
import org.hpb.androidcore.WorkerSession
import org.hpb.protocol.Answer
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import org.hpb.protocol.AssignmentStatus
import org.hpb.protocol.ExternalWork
import org.hpb.protocol.Task
import org.hpb.protocol.WorkSource
import org.hpb.protocol.WorkSurface

@Composable
fun JobsScreen(worker: WorkerSession, tools: ToolAccess? = null) {
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
            AssignmentPanel(worker, job, tools)
            HorizontalDivider()
        }
    }
}

@Composable
private fun AssignmentPanel(
    worker: WorkerSession,
    job: WorkerSession.JobRow,
    tools: ToolAccess?,
) {
    var answers by remember { mutableStateOf(mapOf<String, String>()) }
    var state by remember { mutableStateOf("") }
    val external = job.offer.tasks.mapNotNull { task ->
        ExternalWork.workSource(task.question)?.let { task to it }
    }
    Button(onClick = {
        state = runCatching { submit(worker, job, answers, external, tools) }
            .getOrElse { "error: ${it.message}" }
    }) { Text(if (external.isEmpty()) "Refresh / Submit" else "I've finished in the tool") }
    Text(state)
    job.offer.tasks.forEach { task ->
        val work = external.firstOrNull { it.first.key == task.key }?.second
        if (work != null) {
            ExternalTaskCard(work)
        } else {
            OutlinedTextField(
                value = answers[task.key].orEmpty(),
                onValueChange = { answers = answers + (task.key to it) },
                label = { Text(ExternalWork.displayText(task.question)) },
            )
        }
    }
}

/**
 * Work that lives in another tool: a link out, never a text box.
 *
 * Rendering it as a free-text field — which this screen used to do, showing the
 * raw JSON as the label — invites an answer that can never validate.
 */
@Composable
private fun ExternalTaskCard(work: WorkSource) {
    val context = LocalContext.current
    Text(work.tool.uppercase() + (if (work.surface == WorkSurface.DESKTOP) " · desktop" else ""))
    work.params["labels"]?.takeIf { it.isNotBlank() }?.let { Text("labels: $it") }
    Row {
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(work.url)))
        }) { Text("Open") }
        TextButton(onClick = {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, work.url)
                    },
                    "Send to desktop",
                ),
            )
        }) { Text("Send to desktop") }
    }
}

private fun submit(
    worker: WorkerSession,
    job: WorkerSession.JobRow,
    answers: Map<String, String>,
    external: List<Pair<Task, WorkSource>>,
    tools: ToolAccess?,
): String {
    val mine = worker.assignments(job).firstOrNull()
    if (mine?.status != AssignmentStatus.ACTIVE) {
        return mine?.status?.name?.lowercase() ?: "no assignment yet"
    }
    // Inline answers are kept alongside external ones: a job may carry both,
    // and dropping either half silently loses the worker's work.
    val inline = job.offer.tasks
        .filter { task -> external.none { it.first.key == task.key } }
        .map { Answer(it.key, answers[it.key].orEmpty()) }
    val committed = external.map { (task, work) ->
        val session = requireNotNull(tools) { "sign in to ${work.tool} first" }.session(work.tool)
        val canonical = session.canonicalResult(work)
        worker.commitAndAnswer(
            job.offer.escrowId, task.key, work.params["job_id"].orEmpty(), canonical,
        )
    }
    worker.submit(job, mine, inline + committed)
    return "submitted"
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
