package io.github.knightdwell.physicsfeed

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.knightdwell.physicsfeed.data.Paper
import io.github.knightdwell.physicsfeed.ui.AppViewModel
import io.github.knightdwell.physicsfeed.ui.DetailScreen
import io.github.knightdwell.physicsfeed.ui.FeedScreen
import io.github.knightdwell.physicsfeed.ui.HelpDialog
import io.github.knightdwell.physicsfeed.ui.JournalsScreen
import io.github.knightdwell.physicsfeed.ui.ManageFoldersDialog
import io.github.knightdwell.physicsfeed.ui.PhysicsFeedTheme
import io.github.knightdwell.physicsfeed.ui.SearchScreen
import io.github.knightdwell.physicsfeed.ui.TopicsScreen
import io.github.knightdwell.physicsfeed.ui.UpdatesDialog
import io.github.knightdwell.physicsfeed.ui.View
import io.github.knightdwell.physicsfeed.ui.WelcomeScreen
import io.github.knightdwell.physicsfeed.ui.shareFile
import io.github.knightdwell.physicsfeed.work.FetchWorker
import java.io.File

sealed class Screen {
    data object Feed : Screen()
    data class Detail(val paper: Paper) : Screen()
    data object Search : Screen()
    data object Topics : Screen()
    data object Journals : Screen()
    data class Pdf(val file: java.io.File, val paper: Paper) : Screen()
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FetchWorker.schedule(this)
        handleLink(intent)
        setContent {
            val state by vm.state.collectAsState()
            PhysicsFeedTheme(state.theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(vm) }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** wavepacket://view/<foryou|today|saved|oa|news|all>, wavepacket://source/<journal>, wavepacket://screen/<search|topics|journals> */
    private fun handleLink(i: android.content.Intent?) {
        val u = i?.data ?: return
        if (u.scheme != "wavepacket") return
        val arg = u.pathSegments.firstOrNull() ?: return
        when (u.host) {
            "view" -> vm.linkView.value = View("special", arg)
            "source" -> vm.linkView.value = View("source", arg)
            "screen" -> vm.linkScreen.value = arg
            // wavepacket://import/<base64url settings>: only on a fresh install (never replaces existing settings)
            "import" -> if (vm.firstRun.value) runCatching {
                vm.importConfig(String(android.util.Base64.decode(arg, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)))
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // remember when the app was last open, so the next visit can mark new papers
        vm.markClosed()
    }
}

@Composable
fun App(vm: AppViewModel) {
    val ctx = LocalContext.current
    val firstRun by vm.firstRun.collectAsState()
    val message by vm.message.collectAsState()
    val stack = remember { mutableStateListOf<Screen>(Screen.Feed) }
    var view by remember { mutableStateOf(View.ForYou) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val depthBefore = remember { androidx.compose.runtime.mutableIntStateOf(1) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText() }
            .onSuccess { vm.importConfig(it) }.onFailure { vm.toast("Couldn't read that file") }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.checkUpdate(auto = true)
    }
    val ready by vm.updateReady.collectAsState()
    LaunchedEffect(ready) { if (ready != null && dialog == null) dialog = "updates" }
    LaunchedEffect(message) { message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.toast(null) } }

    if (firstRun) {
        WelcomeScreen(vm, onImport = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) })
        return
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    val linkView by vm.linkView.collectAsState()
    val linkScreen by vm.linkScreen.collectAsState()
    LaunchedEffect(linkView) { linkView?.let { view = it; while (stack.size > 1) stack.removeAt(stack.lastIndex); vm.linkView.value = null } }
    LaunchedEffect(linkScreen) {
        linkScreen?.let {
            when (it) { "search" -> stack.add(Screen.Search); "topics" -> stack.add(Screen.Topics); "journals" -> stack.add(Screen.Journals) }
            vm.linkScreen.value = null
        }
    }

    Box {
        // screens slide in from the right and back out to the right (cheap: translation + fade only)
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = {
                val forward = stack.size > depthBefore.intValue
                depthBefore.intValue = stack.size
                if (forward) (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                else fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(200)))
            },
            label = "screens",
        ) { s ->
        when (s) {
            Screen.Feed -> FeedScreen(vm, view, onView = { view = it }, onOpen = { stack.add(Screen.Detail(it)) }, onNav = { what ->
                when (what) {
                    "search" -> stack.add(Screen.Search)
                    "topics" -> stack.add(Screen.Topics)
                    "journals" -> stack.add(Screen.Journals)
                    "import" -> importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                    "export" -> {
                        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "physics-feed-settings.json")
                        f.writeText(vm.exportConfigText())
                        shareFile(ctx, f, "application/json", "Send my Wavepacket settings")
                    }
                    else -> dialog = what
                }
            })
            is Screen.Detail -> DetailScreen(vm, s.paper, onBack = back, onPdf = { f -> stack.add(Screen.Pdf(f, s.paper)) })
            is Screen.Pdf -> io.github.knightdwell.physicsfeed.ui.PdfScreen(s.file, s.paper, onBack = back)
            Screen.Search -> SearchScreen(vm, onOpen = { stack.add(Screen.Detail(it)) }, onBack = back)
            Screen.Topics -> TopicsScreen(vm, onBack = back)
            Screen.Journals -> JournalsScreen(vm, onBack = back)
        }
        }
        when (dialog) {
            "help" -> HelpDialog { dialog = null }
            "folders" -> ManageFoldersDialog(vm) { dialog = null }
            "updates" -> UpdatesDialog(vm) { dialog = null }
        }
    }
}
