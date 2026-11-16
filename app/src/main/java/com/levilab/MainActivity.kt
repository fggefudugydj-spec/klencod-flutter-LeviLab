package com.levilab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.w("CRASH in ${t.name}: ${e.stackTraceToString().take(2000)}")
            prev?.uncaughtException(t, e)
        }
        AppLog.w("App started. Android ${android.os.Build.VERSION.RELEASE}")
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<PkgInfo?>(null) }
    var libDir by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf(listOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { info = withContext(Dispatchers.IO) { Engine.detect(ctx) } }

    fun work(block: () -> String) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val r = try { block() } catch (t: Throwable) { "Error: $t" }
            withContext(Dispatchers.Main) { status = r; busy = false }
        }
    }

    val labels = listOf(R.string.tab_home, R.string.tab_log, R.string.tab_probe)
    Scaffold(bottomBar = {
        NavigationBar {
            labels.forEachIndexed { i, l ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(stringResource(l)) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            when (tab) {
                0 -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("LeviLab", style = MaterialTheme.typography.headlineMedium)
                    val i = info
                    if (i == null) Text(stringResource(R.string.not_found))
                    else Card { Column(Modifier.padding(12.dp)) {
                        Text("Package: ${i.pkg}")
                        Text("Version: ${i.version}")
                        Text("sourceDir: ${i.sourceDir}", fontSize = 12.sp)
                        Text("splits: ${i.splits.size}", fontSize = 12.sp)
                        i.splits.forEach { Text(it, fontSize = 11.sp) }
                        Text("nativeLibraryDir: ${i.nativeDir}", fontSize = 12.sp)
                        Text("Libs dir: ${libDir?.path ?: "(not verified yet)"}", fontSize = 12.sp)
                    } }
                    Button(onClick = { work { info = Engine.detect(ctx); if (info == null) "Not found" else "Detected ${info!!.pkg}" } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.detect)) }
                    Button(enabled = i != null, onClick = {
                        work { val r = Engine.prepare(ctx, i!!); libDir = r.dir; r.message }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.prepare)) }
                    Button(enabled = i != null, onClick = { work { Engine.launch(ctx, i!!) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.launch)) }
                    OutlinedButton(enabled = i != null, onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.experimental)) }
                    Text(stringResource(R.string.exp_warn), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (status.isNotEmpty()) Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                1 -> Column {
                    LogButtons(ctx)
                    LazyColumn(Modifier.weight(1f)) { items(AppLog.lines.toList().asReversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
                }
                else -> Column {
                    Button(enabled = info != null && !busy, onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val r = try { Engine.probe(ctx, info!!, libDir) } catch (t: Throwable) { listOf("Probe error: $t") }
                            withContext(Dispatchers.Main) { probe = r; busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.run_probe)) }
                    LogButtons(ctx)
                    LazyColumn(Modifier.weight(1f)) { items(probe) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.experimental)) },
        text = { Text(stringResource(R.string.exp_confirm) + "\n" + stringResource(R.string.exp_warn)) },
        confirmButton = { TextButton(onClick = { confirm = false; work { Engine.experimentalLoad(ctx, info!!, libDir) } }) { Text(stringResource(R.string.run)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun LogButtons(ctx: Context) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("LeviLab log", AppLog.read()))
        }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.copy_log), fontSize = 12.sp) }
        OutlinedButton(onClick = {
            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, AppLog.read()) }
            ctx.startActivity(Intent.createChooser(i, null))
        }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.share_log), fontSize = 12.sp) }
        TextButton(onClick = { AppLog.clear() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.clear), fontSize = 12.sp) }
    }
}
