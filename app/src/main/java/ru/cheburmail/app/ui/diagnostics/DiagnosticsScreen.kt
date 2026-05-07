package ru.cheburmail.app.ui.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

private data class Probe(
    val id: String,
    val label: String,
    val host: String,
    val port: Int? = null,
    val kind: ProbeKind
)

private enum class ProbeKind { DNS, TCP, HTTPS_REF }

private enum class Status { IDLE, RUNNING, OK, FAIL }

private data class ProbeResult(
    val status: Status,
    val latencyMs: Long? = null,
    val info: String? = null,
    val error: String? = null
)

private val PROBES = listOf(
    Probe("net", "Сетевой интерфейс", "", null, ProbeKind.HTTPS_REF),

    Probe("mail_dns", "DNS  mail.ru", "mail.ru", null, ProbeKind.DNS),
    Probe("mail_443", "HTTPS  mail.ru:443", "mail.ru", 443, ProbeKind.TCP),
    Probe("mail_smtp_465", "SMTP  smtp.mail.ru:465", "smtp.mail.ru", 465, ProbeKind.TCP),
    Probe("mail_smtp_587", "SMTP  smtp.mail.ru:587", "smtp.mail.ru", 587, ProbeKind.TCP),
    Probe("mail_imap_993", "IMAP  imap.mail.ru:993", "imap.mail.ru", 993, ProbeKind.TCP),

    Probe("yandex_dns", "DNS  yandex.ru", "yandex.ru", null, ProbeKind.DNS),
    Probe("yandex_443", "HTTPS  mail.yandex.ru:443", "mail.yandex.ru", 443, ProbeKind.TCP),
    Probe("yandex_smtp_465", "SMTP  smtp.yandex.ru:465", "smtp.yandex.ru", 465, ProbeKind.TCP),
    Probe("yandex_smtp_587", "SMTP  smtp.yandex.ru:587", "smtp.yandex.ru", 587, ProbeKind.TCP),
    Probe("yandex_imap_993", "IMAP  imap.yandex.ru:993", "imap.yandex.ru", 993, ProbeKind.TCP),

    Probe("rambler_dns", "DNS  rambler.ru", "rambler.ru", null, ProbeKind.DNS),
    Probe("rambler_443", "HTTPS  mail.rambler.ru:443", "mail.rambler.ru", 443, ProbeKind.TCP),
    Probe("rambler_smtp_465", "SMTP  smtp.rambler.ru:465", "smtp.rambler.ru", 465, ProbeKind.TCP),
    Probe("rambler_imap_993", "IMAP  imap.rambler.ru:993", "imap.rambler.ru", 993, ProbeKind.TCP),
)

private const val PROBE_TIMEOUT_MS = 5000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val results = remember {
        mutableStateListOf<ProbeResult>().apply {
            repeat(PROBES.size) { add(ProbeResult(Status.IDLE)) }
        }
    }
    var running by remember { mutableStateOf(false) }
    var diagnosis by remember { mutableStateOf<Diagnosis?>(null) }

    val runProbes: () -> Unit = {
        if (!running) {
            running = true
            diagnosis = null
            for (i in PROBES.indices) results[i] = ProbeResult(Status.RUNNING)
            scope.launch {
                val newResults = withContext(Dispatchers.IO) {
                    coroutineScope {
                        PROBES.mapIndexed { idx, probe ->
                            async {
                                idx to runProbe(ctx, probe)
                            }
                        }.awaitAll()
                    }
                }
                for ((idx, r) in newResults) results[idx] = r
                diagnosis = analyse(results.toList())
                running = false
            }
        }
    }

    LaunchedEffect(Unit) { runProbes() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        DiagnosticsContent(
            padding = padding,
            results = results,
            running = running,
            diagnosis = diagnosis,
            onRetry = runProbes
        )
    }
}

@Composable
private fun DiagnosticsContent(
    padding: PaddingValues,
    results: List<ProbeResult>,
    running: Boolean,
    diagnosis: Diagnosis?,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        items(PROBES.size) { idx ->
            ProbeRow(PROBES[idx].label, results[idx])
        }

        item {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Проверка...")
                } else {
                    Text("Проверить ещё раз")
                }
            }
        }

        diagnosis?.let { d ->
            item {
                Spacer(Modifier.height(12.dp))
                DiagnosisCard(d)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ProbeRow(label: String, result: ProbeResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusBadge(result.status)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            val sub = buildSubtitle(result)
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        result.latencyMs?.let { ms ->
            Text(
                text = "${ms} мс",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBadge(status: Status) {
    val (text, color) = when (status) {
        Status.IDLE -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
        Status.RUNNING -> "…" to MaterialTheme.colorScheme.primary
        Status.OK -> "✓" to Color(0xFF2E7D32)
        Status.FAIL -> "✗" to Color(0xFFC62828)
    }
    Box(
        modifier = Modifier.size(20.dp),
        contentAlignment = Alignment.Center
    ) {
        if (status == Status.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(text = text, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

private fun buildSubtitle(r: ProbeResult): String? {
    val info = r.info
    val err = r.error
    return when {
        err != null -> err
        info != null -> info
        else -> null
    }
}

private data class Diagnosis(val title: String, val body: String, val severity: Severity)
private enum class Severity { OK, WARN, FAIL }

@Composable
private fun DiagnosisCard(d: Diagnosis) {
    val container = when (d.severity) {
        Severity.OK -> Color(0xFFE8F5E9)
        Severity.WARN -> Color(0xFFFFF8E1)
        Severity.FAIL -> Color(0xFFFFEBEE)
    }
    val content = when (d.severity) {
        Severity.OK -> Color(0xFF1B5E20)
        Severity.WARN -> Color(0xFFE65100)
        Severity.FAIL -> Color(0xFFB71C1C)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = d.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(text = d.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private suspend fun runProbe(ctx: Context, probe: Probe): ProbeResult {
    return when (probe.kind) {
        ProbeKind.HTTPS_REF -> probeNetwork(ctx)
        ProbeKind.DNS -> probeDns(probe.host)
        ProbeKind.TCP -> probeTcp(probe.host, probe.port!!)
    }
}

private fun probeNetwork(ctx: Context): ProbeResult {
    return try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ProbeResult(Status.FAIL, error = "ConnectivityManager недоступен")
        val net = cm.activeNetwork
            ?: return ProbeResult(Status.FAIL, error = "Сеть не подключена")
        val caps = cm.getNetworkCapabilities(net)
            ?: return ProbeResult(Status.FAIL, error = "Нет network capabilities")

        val parts = mutableListOf<String>()
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> parts += "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                parts += "Mobile"
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                tm?.networkOperatorName?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> parts += "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> parts += "VPN"
            else -> parts += "Other"
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !parts.contains("VPN")) {
            parts += "VPN"
        }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            parts += "validated"
        } else {
            parts += "not validated"
        }
        ProbeResult(Status.OK, info = parts.joinToString(" · "))
    } catch (e: Exception) {
        ProbeResult(Status.FAIL, error = e.message ?: e.javaClass.simpleName)
    }
}

private fun probeDns(host: String): ProbeResult {
    val start = System.currentTimeMillis()
    return try {
        val addrs = InetAddress.getAllByName(host)
        val latency = System.currentTimeMillis() - start
        val ip = addrs.firstOrNull()?.hostAddress ?: "?"
        val extra = if (addrs.size > 1) " (+${addrs.size - 1})" else ""
        ProbeResult(Status.OK, latencyMs = latency, info = "$ip$extra")
    } catch (e: Exception) {
        val latency = System.currentTimeMillis() - start
        ProbeResult(Status.FAIL, latencyMs = latency, error = e.message ?: e.javaClass.simpleName)
    }
}

private fun probeTcp(host: String, port: Int): ProbeResult {
    val start = System.currentTimeMillis()
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            val latency = System.currentTimeMillis() - start
            ProbeResult(Status.OK, latencyMs = latency)
        }
    } catch (e: Exception) {
        val latency = System.currentTimeMillis() - start
        ProbeResult(Status.FAIL, latencyMs = latency, error = e.message ?: e.javaClass.simpleName)
    }
}

private fun analyse(results: List<ProbeResult>): Diagnosis {
    fun byId(id: String): ProbeResult? {
        val idx = PROBES.indexOfFirst { it.id == id }
        return if (idx >= 0) results[idx] else null
    }

    val mailDns = byId("mail_dns")?.status == Status.OK
    val mail443 = byId("mail_443")?.status == Status.OK
    val mailSmtp = listOf("mail_smtp_465", "mail_smtp_587").any { byId(it)?.status == Status.OK }
    val mailImap = byId("mail_imap_993")?.status == Status.OK

    val yaDns = byId("yandex_dns")?.status == Status.OK
    val ya443 = byId("yandex_443")?.status == Status.OK
    val yaSmtp = listOf("yandex_smtp_465", "yandex_smtp_587").any { byId(it)?.status == Status.OK }
    val yaImap = byId("yandex_imap_993")?.status == Status.OK

    val anyDns = mailDns || yaDns
    val any443 = mail443 || ya443

    val mailFullyOk = mail443 && mailSmtp && mailImap
    val yaFullyOk = ya443 && yaSmtp && yaImap
    val mailFullyDead = !mail443 && !mailSmtp && !mailImap
    val yaFullyDead = !ya443 && !yaSmtp && !yaImap

    val mailReadOnly = mail443 && !mailSmtp && mailImap          // TSPU SMTP-only block (сегодняшний кейс)
    val yaReadOnly = ya443 && !yaSmtp && yaImap

    return when {
        // 1. Полный отказ
        !anyDns && !any443 -> Diagnosis(
            title = "Сеть полностью недоступна",
            body = "Не работает ни DNS, ни HTTPS. Проверь Wi-Fi/мобильные данные и режим «в самолёте».",
            severity = Severity.FAIL
        )

        // 2. DNS заблокирован (HTTPS работает мимо DNS)
        !anyDns && any443 -> Diagnosis(
            title = "DNS заблокирован",
            body = "HTTPS работает, но DNS-резолв падает. Попробуй сменить DNS на 1.1.1.1 / 9.9.9.9 или включи DNS-over-HTTPS в системных настройках.",
            severity = Severity.WARN
        )

        // 3. Всё в порядке
        mailFullyOk && yaFullyOk -> Diagnosis(
            title = "Сеть в порядке",
            body = "Все почтовые порты mail.ru и yandex.ru доступны. Если CheburMail всё равно не отправляет — проблема в учётных данных или логике приложения, а не сети.",
            severity = Severity.OK
        )

        // 4. Сегодняшний кейс: TSPU режет SMTP mail.ru, всё остальное живо
        mailReadOnly && yaFullyOk -> Diagnosis(
            title = "TSPU режет SMTP mail.ru",
            body = "Web и IMAP mail.ru живы, но SMTP (465/587) заблокированы у твоего оператора. Yandex работает полностью. Что делать: используй yandex-аккаунт для отправки или включи VPN. Сам Mail.ru при этом доступен через web/официальное приложение.",
            severity = Severity.FAIL
        )

        // 5. Зеркальная ситуация: режут SMTP yandex
        mailFullyOk && yaReadOnly -> Diagnosis(
            title = "TSPU режет SMTP yandex",
            body = "Mail.ru работает полностью, у yandex заблокированы SMTP-порты (465/587). Что делать: используй mail.ru-аккаунт для отправки или включи VPN.",
            severity = Severity.FAIL
        )

        // 6. Оба провайдера в режиме «читать можно, писать нельзя»
        mailReadOnly && yaReadOnly -> Diagnosis(
            title = "TSPU режет ALL SMTP",
            body = "Web и IMAP обоих провайдеров живы — почту читать можно. Но SMTP (465/587) заблокированы и на mail.ru, и на yandex. Без VPN отправить ничего не получится.",
            severity = Severity.FAIL
        )

        // 7. Mail.ru мёртв полностью, yandex живёт
        mailFullyDead && yaFullyOk -> Diagnosis(
            title = "Mail.ru заблокирован, yandex живёт",
            body = "Mail.ru недоступен ни по одному порту, yandex работает полностью. Используй yandex-аккаунт.",
            severity = Severity.WARN
        )

        // 8. Yandex мёртв полностью, mail.ru живёт
        yaFullyDead && mailFullyOk -> Diagnosis(
            title = "Yandex заблокирован, mail.ru живёт",
            body = "Yandex недоступен ни по одному порту, mail.ru работает полностью. Используй mail.ru-аккаунт.",
            severity = Severity.WARN
        )

        // 9. Оба провайдера полностью убиты
        mailFullyDead && yaFullyDead -> Diagnosis(
            title = "Полная блокировка почтовых провайдеров",
            body = "Ни mail.ru, ни yandex недоступны. Похоже на режим белых списков. CheburMail работать не будет — нужен VPN.",
            severity = Severity.FAIL
        )

        // 10. Только web живёт, ни SMTP ни IMAP — выглядит как whitelist HTTPS-only
        any443 && !mailSmtp && !mailImap && !yaSmtp && !yaImap -> Diagnosis(
            title = "Режим белых списков",
            body = "Web (443) к mail.ru/yandex.ru открывается, но все SMTP и IMAP порты заблокированы. Это TSPU в режиме whitelist HTTPS-only. CheburMail не работает — нужен VPN.",
            severity = Severity.FAIL
        )

        // 11. Что-то ещё (частично-живые ports)
        else -> Diagnosis(
            title = "Смешанный результат",
            body = "Часть проб упала. Смотри детали выше — рабочие порты помечены ✓, заблокированные ✗. Если у одного из провайдеров живы и SMTP, и IMAP — отправка с этого аккаунта должна пройти.",
            severity = Severity.WARN
        )
    }
}

