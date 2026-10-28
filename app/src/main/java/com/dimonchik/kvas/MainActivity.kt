package com.dimonchik.kvas

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dimonchik.kvas.data.ChatTurn
import com.dimonchik.kvas.data.AgentTurn
import com.dimonchik.kvas.data.AgentQuestionBundle
import com.dimonchik.kvas.data.AgentFile
import com.dimonchik.kvas.data.ChatHistoryStore
import com.dimonchik.kvas.data.Completion
import com.dimonchik.kvas.data.ModelInfo
import com.dimonchik.kvas.data.MemoryStore
import com.dimonchik.kvas.data.ProviderApi
import com.dimonchik.kvas.data.ProviderConfig
import com.dimonchik.kvas.data.ProviderStore
import com.dimonchik.kvas.data.SavedChat
import com.dimonchik.kvas.data.SavedMessage
import com.dimonchik.kvas.onboarding.TermuxBridge
import com.dimonchik.kvas.tools.ToolAccess
import com.dimonchik.kvas.tools.ToolAccessStore
import com.dimonchik.kvas.tools.ToolCatalog
import com.dimonchik.kvas.tools.AgentAction
import com.dimonchik.kvas.tools.AgentTask
import com.dimonchik.kvas.tools.BackgroundTask
import com.dimonchik.kvas.tools.FileChange
import com.dimonchik.kvas.tools.AgentRuntime
import com.dimonchik.kvas.tools.ApprovalChoice
import com.dimonchik.kvas.tools.ToolApprovalRequest
import com.dimonchik.kvas.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var setupCompletion by mutableStateOf(false)
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupCompletion = isSetupCompleteIntent(intent)
        setContent { KvasTheme { KvasRoot(this, setupCompletion, resumeTick) { setupCompletion = false } } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isSetupCompleteIntent(intent)) setupCompletion = true
    }

    private fun isSetupCompleteIntent(intent: Intent?): Boolean =
        intent?.data?.scheme == "kvas" && intent.data?.host == "setup-complete"
}

private enum class RootPage { Onboarding, Chat }
private data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val usage: Completion? = null,
    val feedback: String? = null,
    val attachment: String? = null,
    val attachmentUri: String? = null,
    val attachmentText: String? = null
)

@Composable
private fun KvasRoot(activity: MainActivity, setupCompleteSignal: Boolean, resumeTick: Int, clearSetupSignal: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kvas_state", 0) }
    var page by rememberSaveable { mutableStateOf(if (prefs.getBoolean("onboarding_done", false)) RootPage.Chat else RootPage.Onboarding) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var setupPending by rememberSaveable { mutableStateOf(false) }
    var setupStartResume by rememberSaveable { mutableIntStateOf(-1) }
    var setupError by rememberSaveable { mutableStateOf(false) }
    var permissionError by rememberSaveable { mutableStateOf(false) }
    var installStartFailed by rememberSaveable { mutableStateOf(false) }
    var installRunning by rememberSaveable { mutableStateOf(false) }
    var installDone by rememberSaveable { mutableStateOf(false) }
    var installOutput by rememberSaveable { mutableStateOf("") }
    var installFailed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionError = !granted
        if (granted) { step = 2; permissionError = false }
        else runCatching { TermuxBridge.openRunCommandSettings(context) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        step = 5
    }
    val batterySettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        step = 6
    }

    LaunchedEffect(resumeTick, setupPending, setupCompleteSignal) {
        if (setupPending && resumeTick > setupStartResume) {
            // Let Android deliver onNewIntent before deciding whether Termux returned success.
            delay(300)
            if (setupCompleteSignal) {
                setupPending = false
                setupError = false
                step = 4
                clearSetupSignal()
            } else {
                setupPending = false
                setupError = true
                step = 3
            }
        }
    }

    LaunchedEffect(step) {
        if (step == 6 && page == RootPage.Onboarding && !installDone && !installRunning) {
            val installPrefs = context.getSharedPreferences("kvas_install", 0)
            installPrefs.edit().clear().apply()
            installRunning = true
            installStartFailed = !TermuxBridge.installPi(context)
            if (installStartFailed) installRunning = false
        }
    }
    LaunchedEffect(installRunning) {
        while (installRunning) {
            delay(700)
            val result = context.getSharedPreferences("kvas_install", 0)
            if (result.getBoolean("finished", false)) {
                installOutput = result.getString("output", "").orEmpty()
                installDone = result.getInt("exit", -1) == 0
                installFailed = !installDone
                installRunning = false
            }
        }
    }

    BackHandler(enabled = page == RootPage.Onboarding && step > 0) { step-- }
    AnimatedContent(
        targetState = if (page == RootPage.Chat) "chat" else "onboarding-$step",
        transitionSpec = {
            val fromIndex = initialState.removePrefix("onboarding-").toIntOrNull() ?: 7
            val toIndex = targetState.removePrefix("onboarding-").toIntOrNull() ?: 7
            val forward = toIndex > fromIndex
            if (forward) {
                (slideInHorizontally { it } + fadeIn()).togetherWith(slideOutHorizontally { -it } + fadeOut())
            } else {
                (slideInHorizontally { -it } + fadeIn()).togetherWith(slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "kvas-navigation"
    ) { target ->
        if (target == "chat") {
            ChatHome()
        } else {
            OnboardingScreen(
                step = step,
                error = setupError,
                permissionError = permissionError,
                installStartFailed = installStartFailed,
                installRunning = installRunning,
                installDone = installDone,
                installFailed = installFailed,
                installOutput = installOutput,
                onBack = { if (step > 0) step-- },
                onSkip = { step++ },
                onRetryInstall = {
                    context.getSharedPreferences("kvas_install", 0).edit().clear().apply()
                    installStartFailed = false
                    installFailed = false
                    installRunning = true
                    installStartFailed = !TermuxBridge.installPi(context)
                    if (installStartFailed) installRunning = false
                },
                onContinue = {
                    if (step == 6 && installDone) {
                        prefs.edit().putBoolean("onboarding_done", true).apply()
                        page = RootPage.Chat
                    } else if (step < 6) step++
                },
                onAction = {
                    when (step) {
                        0 -> step = 1
                        1 -> {
                            permissionError = false
                            try { termuxPermission.launch(TermuxBridge.RUN_COMMAND_PERMISSION) }
                            catch (_: Exception) {
                                TermuxBridge.openRunCommandSettings(context)
                                permissionError = true
                            }
                        }
                        2, 3 -> {
                            setupError = false
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Команда настройки Кваса", TermuxBridge.setupCommand(context.packageName)))
                            setupStartResume = resumeTick
                            setupPending = true
                            val opened = TermuxBridge.openTermux(context)
                            if (!opened) {
                                setupPending = false
                                setupError = true
                                Toast.makeText(context, "Сначала установите Termux из официального источника.", Toast.LENGTH_LONG).show()
                            }
                        }
                        4 -> {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else step = 5
                        }
                        5 -> {
                            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (pm.isIgnoringBatteryOptimizations(context.packageName)) step = 6
                            else try {
                                batterySettings.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                            } catch (_: Exception) {
                                batterySettings.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun OnboardingScreen(
    step: Int,
    error: Boolean,
    permissionError: Boolean,
    installStartFailed: Boolean,
    installRunning: Boolean,
    installDone: Boolean,
    installFailed: Boolean,
    installOutput: String,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onRetryInstall: () -> Unit,
    onContinue: () -> Unit,
    onAction: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(KvasBackground)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clickable(enabled = step > 0) { onBack() }, contentAlignment = Alignment.CenterStart) {
                if (step > 0) Icon(Icons.Default.ArrowBack, null, tint = KvasText)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(7) { index ->
                    val selected = index == step
                    Box(Modifier.padding(horizontal = 3.dp).width(if (selected) 20.dp else 7.dp).height(7.dp)
                        .clip(if (selected) RoundedCornerShape(6.dp) else CircleShape)
                        .background(if (index <= step) KvasAccent else Color(0xFF2B2B2A)))
                }
            }
            Spacer(Modifier.width(36.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (step) {
                    0 -> {
                        PlaceholderIcon(Icons.Default.LocalCafe)
                        OnbTitle("Добро пожаловать в Квас")
                        OnbBody("Кодинг-агент, который работает на вашем телефоне. Настройка займёт пару минут, и почти всё произойдёт само.")
                    }
                    1 -> {
                        PlaceholderIcon(Icons.Default.Code)
                        OnbTitle("Разрешите Квасу использовать Termux")
                        OnbBody("Квас начинает с отправки команд в Termux.")
                        if (permissionError) OnbError("Предоставьте Квасу разрешение «Выполнять команды в Termux» в настройках приложения.")
                    }
                    2, 3 -> {
                        // Intentionally empty: the HTML prototype's terminal animation was only a visual placeholder.
                        Spacer(Modifier.fillMaxWidth().aspectRatio(1.75f).clip(RoundedCornerShape(16.dp)).background(KvasSurface))
                        OnbTitle("Настройте Termux")
                        if (error || step == 3) OnbError("Termux заблокировал команды Кваса. Выполните команду настройки в Termux и повторите попытку.")
                        OnbBody("Вставьте команду настройки в Termux. Android иногда прерывает установку больших пакетов во время настройки. Если это произошло, просто повторите команду — она продолжит с того места, где остановилась.")
                    }
                    4 -> {
                        OptionalBadge()
                        PlaceholderIcon(Icons.Default.Notifications)
                        OnbTitle("Уведомления")
                        OnbBody("Квас уведомит вас, когда чат завершится или потребуется ваш ответ, пока вас нет рядом.")
                    }
                    5 -> {
                        OptionalBadge()
                        PlaceholderIcon(Icons.Default.BatteryChargingFull)
                        OnbTitle("Работа в фоне")
                        OnbBody("Позволяет чату продолжать работу, пока экран выключен или вы в другом приложении. Без этого Android может прервать агента на середине задачи. Когда Android спросит — нажмите «Разрешить».")
                    }
                    6 -> {
                        PlaceholderIcon(if (installDone) Icons.Default.Check else Icons.Default.HourglassTop, success = installDone)
                        OnbTitle("Установка агента")
                        OnbBody("Квас установит своего агента, Pi 1.4.0, в Termux. Это займёт пару минут.")
                        Spacer(Modifier.height(18.dp))
                        if (installRunning || installStartFailed || installFailed) {
                            Card(colors = CardDefaults.cardColors(containerColor = KvasSurface), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (installRunning) CircularProgressIndicator(Modifier.size(18.dp), color = KvasAccent, strokeWidth = 2.dp)
                                        Text(if (installRunning) "Installing…" else if (installDone) "Установка завершена" else "Не удалось установить Pi", color = KvasMuted, fontSize = 13.sp)
                                    }
                                    if (installRunning || installOutput.isNotBlank()) {
                                        Spacer(Modifier.height(10.dp))
                                        val liveLog = buildString {
                                            if (installRunning) append("\$ npm install -g --ignore-scripts @earendil-works/pi-coding-agent@1.4.0\nОжидаю результат выполнения в Termux…\n")
                                            if (installOutput.isNotBlank()) append(installOutput.takeLast(1200))
                                        }
                                        Text(liveLog, color = KvasDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 10, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (installStartFailed) Text("Не удалось запустить Termux-команду. Проверьте RUN_COMMAND и allow-external-apps.", color = Color(0xFFE4707A), fontSize = 12.sp)
                                }
                            }
                        }
                        if (installDone) OnbBody("Pi 1.4.0 установлен.")
                        if (installFailed) OnbError("Установка завершилась с ошибкой. Проверьте Termux и повторите попытку.")
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            when {
                step == 6 && installDone -> PrimaryButton("Продолжить", onContinue)
                step == 6 && (installFailed || installStartFailed) -> PrimaryButton("Повторить установку", onRetryInstall)
                step == 6 -> Unit
                step == 0 -> PrimaryButton("Начать", onAction)
                step == 1 -> PrimaryButton("Разрешить", onAction)
                step == 2 || step == 3 -> PrimaryButton("Скопировать команду и открыть Termux", {
                    clipboard.setText(AnnotatedString(TermuxBridge.setupCommand("com.dimonchik.kvas")))
                    onAction()
                })
                step == 4 || step == 5 -> {
                    PrimaryButton("Разрешить", onAction)
                    Text("Пропустить", Modifier.fillMaxWidth().clickable { onSkip() }.padding(vertical = 16.dp), color = KvasDim, textAlign = TextAlign.Center, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable private fun PlaceholderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, success: Boolean = false) {
    Box(Modifier.padding(bottom = 25.dp).size(88.dp).clip(RoundedCornerShape(26.dp)).background(if (success) KvasAccent else KvasSurface2), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (success) Color(0xFF18131F) else KvasAccent, modifier = Modifier.size(38.dp))
    }
}
@Composable private fun OptionalBadge() {
    Text("НЕОБЯЗАТЕЛЬНО, НО РЕКОМЕНДУЕТСЯ", Modifier.padding(bottom = 16.dp).clip(CircleShape).background(KvasSurface2).padding(horizontal = 14.dp, vertical = 7.dp), color = KvasAccent, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = .5.sp)
}
@Composable private fun OnbTitle(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(bottom = 14.dp), color = KvasText, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 31.sp, textAlign = TextAlign.Center)
}
@Composable private fun OnbBody(text: String) {
    Text(text, Modifier.fillMaxWidth(), color = KvasDim, fontSize = 15.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
}
@Composable private fun OnbError(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(bottom = 10.dp), color = Color(0xFFE4707A), fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
}
@Composable private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = KvasAccent, contentColor = Color(0xFF18131F))) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHome() {
    val context = LocalContext.current
    val providerStore = remember { ProviderStore(context) }
    val historyStore = remember { ChatHistoryStore(context) }
    val permissionStore = remember { ToolAccessStore(context) }
    val memoryStore = remember { MemoryStore(context) }
    val api = remember { ProviderApi() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val initialChats = remember { historyStore.all() }
    val firstChat = remember { initialChats.firstOrNull() }
    var savedChats by remember { mutableStateOf(initialChats) }
    var historyHydrated by remember { mutableStateOf(false) }
    var currentChatId by rememberSaveable { mutableStateOf(firstChat?.id ?: UUID.randomUUID().toString()) }
    var chatTitle by rememberSaveable { mutableStateOf(firstChat?.title.orEmpty()) }
    var contextSummary by rememberSaveable { mutableStateOf(firstChat?.contextSummary.orEmpty()) }
    var workingDirectory by rememberSaveable {
        mutableStateOf(firstChat?.workingDirectory?.takeIf { it.isNotBlank() }
            ?: context.getSharedPreferences("kvas_state", 0).getString("working_directory", TermuxBridge.TERMUX_HOME).orEmpty())
    }
    var messages by remember {
        mutableStateOf(firstChat?.messages.orEmpty().map {
            UiMessage(
                role = it.role,
                text = it.text,
                usage = Completion("", it.inputTokens, it.outputTokens),
                attachment = it.attachmentName,
                attachmentUri = it.attachmentUri,
                attachmentText = it.attachmentText
            )
        })
    }

    var providers by remember { mutableStateOf(providerStore.all()) }
    var memoryFacts by remember { mutableStateOf(memoryStore.all()) }
    var selectedProvider by remember { mutableStateOf(providers.firstOrNull()) }
    var selectedModel by remember { mutableStateOf(selectedProvider?.models?.firstOrNull()) }
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showProviderForm by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var longWorkStatus by remember { mutableIntStateOf(0) }
    var planExpanded by remember { mutableStateOf(false) }
    var planTasks by remember { mutableStateOf(emptyList<AgentTask>()) }
    var backgroundTasks by remember { mutableStateOf(emptyList<BackgroundTask>()) }
    var backgroundExpanded by remember { mutableStateOf(false) }
    var taskToStop by remember { mutableStateOf<BackgroundTask?>(null) }
    var fileChanges by remember { mutableStateOf(emptyList<FileChange>()) }
    var pendingUndo by remember { mutableStateOf<FileChange?>(null) }
    var agentFiles by remember { mutableStateOf(emptyList<AgentFile>()) }
    var fileToView by remember { mutableStateOf<AgentFile?>(null) }
    var agentActions by remember { mutableStateOf(emptyList<AgentAction>()) }
    var actionsExpanded by remember { mutableStateOf(true) }
    var pendingToolApproval by remember { mutableStateOf<ToolApprovalRequest?>(null) }
    var permissionDeferred by remember { mutableStateOf<CompletableDeferred<ApprovalChoice>?>(null) }
    var activeQuestion by remember { mutableStateOf<AgentQuestionBundle?>(null) }
    var questionDeferred by remember { mutableStateOf<CompletableDeferred<String>?>(null) }
    var questionIndex by remember { mutableIntStateOf(0) }
    var questionAnswers by remember { mutableStateOf(emptyList<String>()) }
    var questionSelection by remember { mutableStateOf(setOf<String>()) }
    var customQuestionAnswer by remember { mutableStateOf("") }
    var toolDetail by remember { mutableStateOf<String?>(null) }
    var showGlobalToolAccess by remember { mutableStateOf(false) }
    var toolVersion by remember { mutableIntStateOf(0) }
    var providerName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var checkBusy by remember { mutableStateOf(false) }
    var checkedModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var checkError by remember { mutableStateOf("") }
    var effort by rememberSaveable { mutableStateOf("Среднее") }
    var effortExpanded by remember { mutableStateOf(false) }
    var modelSearch by remember { mutableStateOf("") }
    var chatSearch by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf("") }
    var systemInstructions by rememberSaveable {
        mutableStateOf(context.getSharedPreferences("kvas_settings", 0).getString("instructions", "").orEmpty())
    }
    var webSearchEnabled by rememberSaveable { mutableStateOf(false) }
    var memoryEnabled by rememberSaveable { mutableStateOf(true) }
    var costInfo by remember { mutableStateOf<String?>(null) }
    var pickedUri by remember { mutableStateOf<String?>(null) }
    var lastUsage by remember { mutableStateOf<Completion?>(messages.lastOrNull { it.role == "assistant" }?.usage) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pickedUri = uri.toString()
            picked = resolveDisplayName(context, uri, "Фото")
        }
        showAttach = false
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pickedUri = uri.toString()
            picked = resolveDisplayName(context, uri, "Файл")
        }
        showAttach = false
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write("# ${chatTitle.ifBlank { "Чат Кваса" }}\n\n")
                messages.forEach { writer.write("${if (it.role == "user") "## Вы" else "## Квас"}\n\n${it.text}\n\n") }
            }
        }.onFailure { Toast.makeText(context, "Не удалось экспортировать чат", Toast.LENGTH_SHORT).show() }
    }
    val fileDownloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) fileToView?.let { file ->
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) } }
                .onFailure { Toast.makeText(context, "Не удалось сохранить файл.", Toast.LENGTH_LONG).show() }
        }
    }
    val fileMarkdownPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) fileToView?.let { file ->
            val content = file.textContent ?: "# ${file.name}\n\nБинарный файл сохранён как вложение и недоступен для текстового экспорта."
            runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) } }
                .onFailure { Toast.makeText(context, "Не удалось сохранить MD.", Toast.LENGTH_LONG).show() }
        }
    }
    val filePdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) fileToView?.let { file ->
            val source = file.textContent ?: "${file.name}\n\nПредпросмотр содержимого недоступен."
            runCatching {
                val pdf = createSimplePdf(source)
                context.contentResolver.openOutputStream(uri)?.use { it.write(pdf) }
            }.onFailure { Toast.makeText(context, "Не удалось сохранить PDF.", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(messages, chatTitle, workingDirectory, currentChatId) {
        if (messages.isNotEmpty()) {
            historyStore.save(
                SavedChat(
                    id = currentChatId,
                    title = chatTitle.ifBlank { messages.firstOrNull { it.role == "user" }?.text?.take(44).orEmpty() },
                    workingDirectory = workingDirectory,
                    contextSummary = contextSummary,
                    messages = messages.map {
                        SavedMessage(
                            it.role, it.text, it.usage?.inputTokens, it.usage?.outputTokens,
                            it.attachment, it.attachmentUri, it.attachmentText
                        )
                    }
                )
            )
            savedChats = historyStore.all()
        }
    }

    fun startNewChat() {
        currentChatId = UUID.randomUUID().toString()
        chatTitle = ""
        contextSummary = ""
        messages = emptyList()
        lastUsage = null
        picked = ""
        pickedUri = null
        goal = ""
        agentActions = emptyList()
        planTasks = emptyList()
        fileChanges = emptyList()
        agentFiles = emptyList()
    }

    fun openSavedChat(chat: SavedChat) {
        currentChatId = chat.id
        chatTitle = chat.title
        contextSummary = chat.contextSummary
        workingDirectory = chat.workingDirectory.ifBlank { TermuxBridge.TERMUX_HOME }
        messages = chat.messages.map {
            UiMessage(
                role = it.role,
                text = it.text,
                usage = Completion("", it.inputTokens, it.outputTokens),
                attachment = it.attachmentName,
                attachmentUri = it.attachmentUri,
                attachmentText = it.attachmentText
            )
        }
        lastUsage = messages.lastOrNull { it.role == "assistant" }?.usage
        agentActions = emptyList()
        planTasks = emptyList()
        fileChanges = emptyList()
        agentFiles = emptyList()
    }

    fun resolveToolApproval(choice: ApprovalChoice) {
        permissionDeferred?.complete(choice)
        permissionDeferred = null
        pendingToolApproval = null
    }

    fun resolveQuestionAnswer(answer: String) {
        val bundle = activeQuestion ?: return
        val current = bundle.questions.getOrNull(questionIndex)
        val answers = questionAnswers + "${current?.title.orEmpty()}\n$answer"
        if (questionIndex + 1 < bundle.questions.size) {
            questionAnswers = answers
            questionIndex++
            questionSelection = emptySet()
            customQuestionAnswer = ""
        } else {
            questionDeferred?.complete(answers.joinToString("\n\n"))
            questionDeferred = null
            activeQuestion = null
            questionAnswers = emptyList()
            questionSelection = emptySet()
            customQuestionAnswer = ""
        }
    }

    fun createRuntime(): AgentRuntime = AgentRuntime(
        context = context,
        api = api,
        permissions = permissionStore,
        memoryStore = memoryStore,
        askPermission = { request ->
            val deferred = CompletableDeferred<ApprovalChoice>()
            pendingToolApproval = request
            permissionDeferred = deferred
            deferred.await()
        },
        askUser = { bundle ->
            val deferred = CompletableDeferred<String>()
            activeQuestion = bundle
            questionDeferred = deferred
            questionIndex = 0
            questionAnswers = emptyList()
            questionSelection = emptySet()
            customQuestionAnswer = ""
            deferred.await()
        },
        onAction = { action ->
            agentActions = if (agentActions.any { it.id == action.id }) {
                agentActions.map { if (it.id == action.id) action else it }
            } else agentActions + action
        },
        onPlan = { tasks -> planTasks = tasks },
        onBackground = { tasks -> backgroundTasks = tasks },
        onFileChange = { change -> fileChanges = fileChanges + change },
        onFile = { file -> agentFiles = agentFiles + file }
    )

    LaunchedEffect(workingDirectory) {
        if (TermuxBridge.hasPermission(context)) {
            backgroundTasks = runCatching { createRuntime().listBackgroundTasks(workingDirectory) }
                .getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) {
        if (TermuxBridge.hasPermission(context)) {
            val requestId = TermuxBridge.runWorkspaceOperation(
                context,
                TermuxBridge.TERMUX_HOME,
                "load_chat_history",
                JSONObject()
            )
            if (requestId != null) {
                val prefs = context.getSharedPreferences(TermuxBridge.RPC_PREFS, Context.MODE_PRIVATE)
                val deadline = System.currentTimeMillis() + 20_000
                while (!prefs.getBoolean("$requestId.finished", false) && System.currentTimeMillis() < deadline) delay(250)
                if (prefs.getBoolean("$requestId.finished", false) && prefs.getInt("$requestId.exit", -1) == 0) {
                    val raw = prefs.getString("$requestId.stdout", "").orEmpty()
                    val serialized = runCatching {
                        val line = raw.trim().lineSequence().last { it.startsWith("{") }
                        JSONObject(line).optJSONArray("result")?.toString()
                    }.getOrNull()
                    if (!serialized.isNullOrBlank() && historyStore.mergeJson(serialized)) {
                        savedChats = historyStore.all()
                        if (messages.isEmpty()) savedChats.firstOrNull()?.let { chat ->
                            currentChatId = chat.id
                            chatTitle = chat.title
                            contextSummary = chat.contextSummary
                            workingDirectory = chat.workingDirectory.ifBlank { TermuxBridge.TERMUX_HOME }
                            messages = chat.messages.map { saved ->
                                UiMessage(
                                    role = saved.role,
                                    text = saved.text,
                                    usage = Completion("", saved.inputTokens, saved.outputTokens),
                                    attachment = saved.attachmentName,
                                    attachmentUri = saved.attachmentUri,
                                    attachmentText = saved.attachmentText
                                )
                            }
                        }
                    }
                }
                prefs.edit().remove("$requestId.finished").remove("$requestId.exit")
                    .remove("$requestId.stdout").remove("$requestId.stderr").apply()
            }
        }
        historyHydrated = true
    }
    LaunchedEffect(savedChats, historyHydrated) {
        if (!historyHydrated || !TermuxBridge.hasPermission(context) || savedChats.isEmpty()) return@LaunchedEffect
        delay(1_000)
        val payload = historyStore.exportJson()
        if (payload.length < 1_500_000) {
            TermuxBridge.runWorkspaceOperation(
                context,
                TermuxBridge.TERMUX_HOME,
                "store_chat_history",
                JSONObject().put("content", payload)
            )
        }
    }
    LaunchedEffect(busy) {
        if (!busy) {
            longWorkStatus = 0
        } else {
            delay(8_000)
            while (busy) {
                longWorkStatus = (longWorkStatus + 1) % 4
                delay(7_000)
            }
        }
    }

    fun handleCommand(commandLine: String) {
        val parts = commandLine.trim().split(Regex("\\s+"), limit = 2)
        val command = parts.firstOrNull().orEmpty()
        val argument = parts.getOrNull(1).orEmpty()
        when (command) {
            "/models" -> showModels = true
            "/context" -> showContext = true
            "/plan" -> planExpanded = true
            "/goal" -> {
                if (argument.isNotBlank()) {
                    goal = argument
                    Toast.makeText(context, "Цель сессии сохранена", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "Добавьте цель после команды /goal", Toast.LENGTH_SHORT).show()
            }
            "/export" -> exportPicker.launch("kvas-chat.md")
            "/cost" -> {
                val input = messages.sumOf { it.usage?.inputTokens ?: 0L }
                val output = messages.sumOf { it.usage?.outputTokens ?: 0L }
                costInfo = "Токены в этом чате: вход $input · выход $output. Стоимость не рассчитана: провайдер не передал тарифы модели."
            }
            "/memory" -> {
                memoryFacts = memoryStore.all()
                costInfo = if (memoryFacts.isEmpty()) "Память пока пуста."
                else "Локальная память о вас и проектах:\n\n" + memoryFacts.joinToString("\n") { "• ${it.fact}" }
            }
            "/compact" -> {
                val provider = selectedProvider
                val model = selectedModel
                if (provider == null || model == null || messages.isEmpty()) {
                    Toast.makeText(context, "Нужна подключённая модель и история чата.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Сжимаю контекст…", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        runCatching {
                            val transcript = buildString {
                                messages.forEach { append(if (it.role == "user") "Пользователь: " else "Квас: ").append(it.text).append("\n\n") }
                                agentActions.forEach { append("Действие агента: ").append(it.title).append(" — ").append(it.detail).append("\n") }
                            }
                            api.complete(provider, model, listOf(
                                ChatTurn("system", "Сожми историю в компактную, точную сводку для продолжения задачи. Сохрани цель, важные решения, имена файлов, незавершённые пункты и ограничения. Не выдумывай факты."),
                                ChatTurn("user", transcript)
                            ), effort).text
                        }.onSuccess { summary ->
                            contextSummary = summary
                            Toast.makeText(context, "Контекст сжат и будет добавлен к следующим запросам.", Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(context, "Не удалось сжать контекст: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            "/agents" -> Toast.makeText(context, "Вспомогательные агенты пока не подключены.", Toast.LENGTH_LONG).show()
            "/undo" -> {
                val last = fileChanges.lastOrNull()
                if (last == null) Toast.makeText(context, "В этом чате пока нет изменений файлов.", Toast.LENGTH_SHORT).show()
                else pendingUndo = last
            }
            "/diff" -> {
                costInfo = if (fileChanges.isEmpty()) "В этом чате пока нет изменений файлов."
                else fileChanges.joinToString("\n\n") { change ->
                    val kind = if (change.before == null) "Создан" else "Изменён"
                    "$kind: ${change.path}\n${change.after.lines().take(4).joinToString("\n")}"
                }
            }
            else -> Toast.makeText(context, "Неизвестная команда", Toast.LENGTH_SHORT).show()
        }
        text = ""
    }

    val slashCommands = listOf(
        "/models" to "switch model", "/compact" to "compact context", "/agents" to "manage subagents",
        "/goal" to "set session goal", "/plan" to "show task plan", "/undo" to "undo last file change",
        "/diff" to "show file changes", "/export" to "export chat", "/cost" to "show chat cost",
        "/context" to "context window details", "/memory" to "show saved memory"
    )
    val slashMatches = if (text.startsWith("/") && !text.contains(' ')) slashCommands.filter { it.first.startsWith(text, true) } else emptyList()

    Column(Modifier.fillMaxSize().background(KvasBackground)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showDrawer = true }) { Icon(Icons.Default.Menu, "Меню", tint = KvasText) }
            Text(chatTitle, Modifier.weight(1f), color = KvasMuted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            IconButton(onClick = { costInfo = "Меню чата: история сохраняется автоматически на устройстве." }) { Icon(Icons.Default.MoreVert, "Меню чата", tint = KvasText) }
        }
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LocalCafe, null, tint = KvasAccent, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Чем займёмся?", color = KvasMuted, fontSize = 19.sp)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(messages, key = { it.id }) { msg ->
                    if (msg.role == "user") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Column(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(14.dp)).background(KvasSurface2).padding(12.dp)) {
                                Text(msg.text, color = KvasText, fontSize = 15.sp, lineHeight = 22.sp)
                                msg.attachment?.let { Text("📎 $it", Modifier.padding(top = 6.dp), color = KvasMuted, fontSize = 12.sp) }
                            }
                        }
                    } else {
                        Column {
                            MarkdownContent(msg.text, Modifier.fillMaxWidth())
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentCopy, "Копировать", Modifier.size(17.dp).clickable { clipboard.setText(AnnotatedString(msg.text)) }, tint = KvasDim)
                                Icon(Icons.Default.Share, "Поделиться", Modifier.size(17.dp).clickable {
                                    runCatching {
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg.text)
                                        }, "Поделиться ответом"))
                                    }
                                }, tint = KvasDim)
                                Icon(if (msg.feedback == "like") Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt, "Нравится", Modifier.size(17.dp).clickable {
                                    messages = messages.map { if (it.id == msg.id) it.copy(feedback = if (it.feedback == "like") null else "like") else it }
                                }, tint = if (msg.feedback == "like") KvasMuted else KvasDim)
                                Icon(if (msg.feedback == "dislike") Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt, "Не нравится", Modifier.size(17.dp).clickable {
                                    messages = messages.map { if (it.id == msg.id) it.copy(feedback = if (it.feedback == "dislike") null else "dislike") else it }
                                }, tint = if (msg.feedback == "dislike") KvasMuted else KvasDim)
                            }
                            if (msg.usage != null) {
                                Text("in ${msg.usage.inputTokens ?: 0} · out ${msg.usage.outputTokens ?: 0} токенов", color = KvasDim, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                            }
                            if (msg.id == messages.lastOrNull { it.role == "assistant" }?.id) {
                                Text("Квас может ошибаться.", color = KvasDim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
                if (agentActions.isNotEmpty()) {
                    item(key = "agent-activity-feed") {
                        AgentActivityFeed(agentActions, actionsExpanded) { actionsExpanded = !actionsExpanded }
                    }
                }
                agentFiles.forEachIndexed { index, file ->
                    item(key = "agent-file-$index-${file.name}") {
                        AgentFileCard(file) { fileToView = file }
                    }
                }
                if (busy) item {
                    val statuses = listOf("Квас работает…", "Ещё изучаю задачу…", "Проверяю результат…", "Продолжаю работу…")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = KvasAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(statuses[longWorkStatus], color = KvasDim, fontSize = 13.sp)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (slashMatches.isNotEmpty()) {
                Surface(color = KvasSurface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Column {
                        slashMatches.forEach { (name, description) ->
                            Row(Modifier.fillMaxWidth().clickable { text = "$name " }.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(name, color = KvasText, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                Text(description, color = KvasDim, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            if (backgroundTasks.isNotEmpty()) {
                Surface(color = KvasSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { backgroundExpanded = !backgroundExpanded }.padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Фон ${backgroundTasks.size}", color = KvasMuted, fontSize = 12.sp)
                            Text(if (backgroundExpanded) "Свернуть" else "Показать", color = KvasDim, fontSize = 12.sp)
                        }
                        if (backgroundExpanded) backgroundTasks.forEach { task ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(task.description, color = KvasText, fontSize = 12.sp)
                                    Text(task.command, color = KvasDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 2)
                                    Text("с ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(task.startedAt))}", color = KvasDim, fontSize = 10.sp)
                                }
                                IconButton(onClick = { taskToStop = task }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, "Остановить задачу", tint = KvasMuted, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(KvasSurface).padding(10.dp)) {
                activeQuestion?.let { bundle ->
                    val question = bundle.questions.getOrNull(questionIndex)
                    if (question != null) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(KvasSurface2).padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Вопрос", Modifier.weight(1f), color = KvasText, fontWeight = FontWeight.SemiBold)
                                if (bundle.questions.size > 1) Text("${questionIndex + 1} из ${bundle.questions.size}", color = KvasDim, fontSize = 11.sp)
                                IconButton(onClick = { resolveQuestionAnswer("Пользователь закрыл вопрос") }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Close, "Закрыть вопрос", tint = KvasMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(question.title, Modifier.padding(vertical = 6.dp), color = KvasText, fontSize = 14.sp)
                            question.options.forEachIndexed { index, option ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        if (question.multiple) {
                                            questionSelection = if (option in questionSelection) questionSelection - option else questionSelection + option
                                        } else resolveQuestionAnswer(option)
                                    }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (question.multiple) Checkbox(option in questionSelection, onCheckedChange = {
                                        questionSelection = if (it) questionSelection + option else questionSelection - option
                                    }, modifier = Modifier.size(26.dp))
                                    else Text("${index + 1}.", Modifier.width(30.dp), color = KvasMuted, fontSize = 13.sp)
                                    Text(option, color = KvasText, fontSize = 13.sp)
                                }
                                if (index < question.options.lastIndex) HorizontalDivider(color = KvasBorder)
                            }
                            BasicTextField(
                                customQuestionAnswer,
                                { customQuestionAnswer = it },
                                Modifier.fillMaxWidth().padding(top = 7.dp).background(KvasSurface).padding(10.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(color = KvasText, fontSize = 13.sp),
                                decorationBox = { inner -> Box {
                                    if (customQuestionAnswer.isBlank()) Text("Введите свой ответ…", color = KvasDim, fontSize = 13.sp)
                                    inner()
                                } }
                            )
                            if (question.multiple) {
                                Button(
                                    onClick = {
                                        val answer = buildList {
                                            addAll(questionSelection)
                                            if (customQuestionAnswer.isNotBlank()) add(customQuestionAnswer.trim())
                                        }.joinToString(", ")
                                        resolveQuestionAnswer(answer.ifBlank { "Ничего не выбрано" })
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    enabled = questionSelection.isNotEmpty() || customQuestionAnswer.isNotBlank()
                                ) { Text("Отправить") }
                            } else if (customQuestionAnswer.isNotBlank()) {
                                TextButton(onClick = { resolveQuestionAnswer(customQuestionAnswer.trim()) }) {
                                    Text("Отправить свой ответ", color = KvasAccent)
                                }
                            }
                        }
                    }
                }
                if (planTasks.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { planExpanded = !planExpanded }.padding(start = 6.dp, bottom = 6.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Задачи ${planTasks.size}", color = KvasMuted, fontSize = 12.sp)
                        Text(if (planExpanded) "Свернуть" else "Показать", color = KvasDim, fontSize = 12.sp)
                    }
                    if (planExpanded) {
                        planTasks.forEach { task ->
                            Row(Modifier.padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = task.completed, onCheckedChange = null, modifier = Modifier.size(28.dp))
                                Text(task.title, color = if (task.completed) KvasDim else KvasMuted, fontSize = 12.sp,
                                    textDecoration = if (task.completed) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                            }
                        }
                    }
                } else if (planExpanded) {
                    Text("План задач пока не создан агентом.", Modifier.padding(start = 6.dp, bottom = 6.dp), color = KvasMuted, fontSize = 12.sp)
                }
                BasicTextField(value = text, onValueChange = { text = it }, Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(horizontal = 5.dp, vertical = 6.dp), textStyle = androidx.compose.ui.text.TextStyle(color = KvasText, fontSize = 15.sp, lineHeight = 21.sp), decorationBox = { inner ->
                    Box { if (text.isEmpty()) Text("Напиши агенту", color = KvasDim, fontSize = 15.sp); inner() }
                })
                if (picked.isNotBlank()) Text("Прикреплено: $picked", color = KvasMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp, bottom = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showAttach = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Add, "Добавить", tint = KvasMuted) }
                    TextButton(onClick = { showModels = true }) { Text(selectedModel?.id ?: "Подключите модель", color = KvasMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.weight(1f))
                    ContextRing(lastUsage?.inputTokens ?: 0L, selectedModel?.contextLimit, onClick = { showContext = true })
                    IconButton(onClick = {
                        if (text.isNotBlank() && !busy) {
                            val prompt = text.trim()
                            if (prompt.startsWith("/")) { handleCommand(prompt); return@IconButton }
                            text = ""
                            val attachment = picked.takeIf { it.isNotBlank() }
                            val attachmentUri = pickedUri
                            picked = ""
                            pickedUri = null
                            val updated = messages + UiMessage(
                                role = "user",
                                text = prompt,
                                attachment = attachment,
                                attachmentUri = attachmentUri
                            )
                            messages = updated
                            agentActions = emptyList()
                            actionsExpanded = true
                            busy = true
                            val provider = selectedProvider
                            val model = selectedModel
                            if (provider == null || model == null) {
                                messages = updated + UiMessage(role = "assistant", text = "Подключите провайдера и выберите модель, чтобы начать работу.")
                                busy = false
                            } else scope.launch {
                                try {
                                    val runtime = createRuntime()
                                    val messagesWithTextFiles = updated.map { message ->
                                        if (message.attachmentText != null || message.attachmentUri == null) message
                                        else {
                                            val uri = Uri.parse(message.attachmentUri)
                                            val mime = context.contentResolver.getType(uri).orEmpty()
                                            val fileText = if (mime.startsWith("image/")) null
                                                else readTextAttachment(context, uri, message.attachment.orEmpty())
                                            message.copy(attachmentText = fileText)
                                        }
                                    }
                                    messages = messagesWithTextFiles
                                    val requestTurns = buildList {
                                        add(AgentTurn("system", buildString {
                                            append("Ты — Квас, русскоязычный помощник по программированию. Рабочая директория: ")
                                            append(workingDirectory)
                                            append(".")
                                            if (goal.isNotBlank()) append(" Цель сессии: $goal.")
                                            if (systemInstructions.isNotBlank()) append(" Глобальные инструкции пользователя: $systemInstructions")
                                            val memory = if (memoryEnabled) memoryStore.all() else emptyList()
                                            if (memory.isNotEmpty()) append(" Долгосрочная память: ${memory.joinToString("; ") { it.fact }}.")
                                            if (contextSummary.isNotBlank()) append(" Сжатая сводка предыдущей истории: $contextSummary")
                                            if (webSearchEnabled) append(" Поиск в интернете разрешён только для общедоступной информации.")
                                            append(" Для многошаговой работы создай и обновляй план через update_plan. Все файловые операции ограничены выбранной рабочей папкой. Не выходи за её пределы. Не утверждай, что проверка или сборка прошла, если не запускал её.")
                                        }))
                                        (if (contextSummary.isBlank()) messagesWithTextFiles else messagesWithTextFiles.takeLast(8)).forEach { message ->
                                            val content = if (message.attachmentText.isNullOrBlank()) message.text
                                                else "${message.text}\n\n[Содержимое файла ${message.attachment}]\n${message.attachmentText}"
                                            val images = message.attachmentUri?.let { uriString ->
                                                val uri = Uri.parse(uriString)
                                                if (context.contentResolver.getType(uri).orEmpty().startsWith("image/")) {
                                                    listOf(loadImageDataUri(context, uri))
                                                } else emptyList()
                                            }.orEmpty()
                                            add(AgentTurn(message.role, content, imageDataUris = images))
                                        }
                                    }
                                    val result = runtime.run(
                                        provider, model, requestTurns, effort, workingDirectory,
                                        webSearchEnabled, memoryEnabled
                                    )
                                    memoryFacts = memoryStore.all()
                                    val completion = Completion(result.text, result.inputTokens, result.outputTokens)
                                    messages = messages + UiMessage(role = "assistant", text = result.text, usage = completion)
                                    lastUsage = completion
                                    if (chatTitle.isBlank()) {
                                        val titleResponse = runCatching {
                                            api.complete(provider, model, listOf(
                                                ChatTurn("system", "Дай чату короткое название на русском, 2–5 слов. Верни только название без кавычек и точки."),
                                                ChatTurn("user", prompt)
                                            ), "Низкое").text
                                        }.getOrNull()
                                        chatTitle = titleResponse?.lineSequence()?.firstOrNull()?.trim()?.trim('"', '«', '»', '.', ' ')?.take(48)
                                            ?.takeIf { it.isNotBlank() } ?: prompt.take(40)
                                    }
                                } catch (e: Exception) {
                                    messages = messages + UiMessage(role = "assistant", text = "Не удалось получить ответ: ${e.message ?: "ошибка соединения"}")
                                } finally { busy = false }
                            }
                        }
                    }, enabled = text.isNotBlank() && !busy, modifier = Modifier.size(38.dp).clip(CircleShape).background(if (text.isNotBlank()) KvasAccent else KvasSurface2)) {
                        Icon(Icons.Default.ArrowUpward, "Отправить", tint = if (text.isNotBlank()) Color(0xFF18131F) else KvasDim, modifier = Modifier.size(19.dp))
                    }
                }
            }
            lastUsage?.let { usage ->
                Text("in ${usage.inputTokens ?: 0} · out ${usage.outputTokens ?: 0} · стоимость не предоставлена провайдером", Modifier.padding(start = 10.dp, top = 4.dp), color = KvasDim, fontSize = 10.sp, maxLines = 1)
            }
        }
    }

    if (showDrawer) ModalBottomSheet(onDismissRequest = { showDrawer = false }, containerColor = KvasSurface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Квас", Modifier.weight(1f), color = KvasText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { chatSearch = if (chatSearch.isBlank()) " " else "" }) { Icon(Icons.Default.Search, "Поиск чатов", tint = KvasMuted) }
        }
        if (chatSearch.isNotBlank()) {
            BasicTextField(chatSearch.trimStart(), { chatSearch = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = KvasText), decorationBox = { inner -> Box { if (chatSearch.trim().isEmpty()) Text("Поиск по чатам", color = KvasDim); inner() } })
        }
        listOf("Проекты", "Артефакты", "Папки", "Тулы", "Настройки").forEach { item ->
            ListItem(headlineContent = { Text(item, color = KvasText) }, leadingContent = { Icon(when (item) { "Папки" -> Icons.Default.Folder; "Настройки" -> Icons.Default.Settings; "Тулы" -> Icons.Default.Build; else -> Icons.Default.Circle }, null, tint = KvasMuted) }, modifier = Modifier.clickable {
                showDrawer = false
                when (item) {
                    "Папки" -> showFolders = true
                    "Тулы" -> showTools = true
                    "Настройки" -> showSettings = true
                    "Проекты" -> Toast.makeText(context, "Недоступно. В разработке.", Toast.LENGTH_SHORT).show()
                }
            }, colors = ListItemDefaults.colors(containerColor = KvasSurface))
        }
        Text("Недавние", Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp), color = KvasDim, fontSize = 12.sp)
        val filteredChats = savedChats.filter { chat ->
            chatSearch.isBlank() || chat.title.contains(chatSearch.trim(), true) || chat.messages.any { it.text.contains(chatSearch.trim(), true) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            items(filteredChats, key = { it.id }) { chat ->
                ListItem(headlineContent = { Text(chat.title.ifBlank { "Новый чат" }, color = KvasText, maxLines = 1) },
                    supportingContent = { Text(chat.workingDirectory.removePrefix(TermuxBridge.TERMUX_HOME).ifBlank { "Недавно" }, color = KvasDim, maxLines = 1) },
                    modifier = Modifier.clickable { openSavedChat(chat); showDrawer = false }, colors = ListItemDefaults.colors(containerColor = KvasSurface))
            }
        }
        TextButton(onClick = { startNewChat(); showDrawer = false }) { Text("+ Новая сессия", color = KvasAccent) }
        Spacer(Modifier.height(20.dp))
    }

    if (showFolders) TermuxFolderPicker(onDismiss = { showFolders = false }, onUseFolder = { folder ->
        workingDirectory = folder
        context.getSharedPreferences("kvas_state", 0).edit().putString("working_directory", folder).apply()
        showFolders = false
        Toast.makeText(context, "Рабочая папка выбрана", Toast.LENGTH_SHORT).show()
    })

    if (showAttach) ModalBottomSheet(onDismissRequest = { showAttach = false }, containerColor = KvasSurface) {
        Text("Добавить в чат", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = KvasText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AttachmentCard("Фото", Icons.Default.Image, Modifier.weight(1f)) { photoPicker.launch(arrayOf("image/*")) }
            AttachmentCard("Файлы", Icons.Default.InsertDriveFile, Modifier.weight(1f)) { filePicker.launch(arrayOf("*/*")) }
        }
        Row(Modifier.fillMaxWidth().clickable { webSearchEnabled = !webSearchEnabled }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Поиск в интернете", Modifier.weight(1f), color = KvasText)
            Switch(checked = webSearchEnabled, onCheckedChange = { webSearchEnabled = it })
        }
        Row(Modifier.fillMaxWidth().clickable { memoryEnabled = !memoryEnabled }.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Память", Modifier.weight(1f), color = KvasText)
            Switch(checked = memoryEnabled, onCheckedChange = { memoryEnabled = it })
        }
        TextButton(onClick = { showAttach = false; showTools = true }) { Icon(Icons.Default.Build, null); Spacer(Modifier.width(8.dp)); Text("Тулы", color = KvasAccent) }
        Spacer(Modifier.height(18.dp))
    }

    if (showTools) ModalBottomSheet(onDismissRequest = { showTools = false; toolDetail = null }, containerColor = KvasSurface) {
        Text("Тулы", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = KvasText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().clickable { showGlobalToolAccess = true }.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Задать для всех инструментов", color = KvasText)
            Text(permissionStore.uniformAccess()?.let { accessLabel(it) } ?: "Пользовательская", color = Color(0xFF78A9FF))
        }
        HorizontalDivider(color = KvasBorder)
        ToolCatalog.tools.forEach { tool ->
            key(tool.name, toolVersion) {
                ListItem(headlineContent = { Text(tool.name, color = KvasText, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    supportingContent = { Text(tool.description, color = KvasDim, fontSize = 12.sp) },
                    trailingContent = { Text(accessLabel(permissionStore.get(tool.name)), color = Color(0xFF78A9FF), fontSize = 12.sp) },
                    modifier = Modifier.clickable { toolDetail = tool.name }, colors = ListItemDefaults.colors(containerColor = KvasSurface))
            }
        }
        Spacer(Modifier.height(18.dp))
    }

    if (showGlobalToolAccess) AlertDialog(onDismissRequest = { showGlobalToolAccess = false }, title = { Text("Задать для всех инструментов") }, text = {
        Column {
            listOf(ToolAccess.ALWAYS_ALLOW, ToolAccess.ASK, ToolAccess.NEVER).forEach { access ->
                val description = when (access) {
                    ToolAccess.ALWAYS_ALLOW -> "Выполнять без повторного запроса разрешения"
                    ToolAccess.ASK -> "Квас запрашивает подтверждение перед действием"
                    ToolAccess.NEVER -> "Квас не сможет использовать эти инструменты"
                }
                TextButton(onClick = { permissionStore.setAll(access); toolVersion++; showGlobalToolAccess = false }) {
                    Column(Modifier.fillMaxWidth()) { Text(accessLabel(access), color = Color(0xFF78A9FF)); Text(description, color = KvasDim, fontSize = 12.sp) }
                    if (permissionStore.uniformAccess() == access) Icon(Icons.Default.Check, null, tint = Color(0xFF78A9FF))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showGlobalToolAccess = false }) { Text("Закрыть") } })

    toolDetail?.let { name ->
        val spec = ToolCatalog.tools.firstOrNull { it.name == name }
        AlertDialog(onDismissRequest = { toolDetail = null }, title = { Text(name) }, text = {
            Column {
                Text(spec?.description.orEmpty(), color = KvasMuted)
                spec?.caution?.let { Text(it, Modifier.padding(top = 8.dp), color = Color(0xFFE9B66D), fontSize = 12.sp) }
                listOf(ToolAccess.ASK, ToolAccess.ALWAYS_ALLOW, ToolAccess.NEVER).forEach { access ->
                    TextButton(onClick = { permissionStore.set(name, access); toolVersion++; toolDetail = null }) {
                        Text(accessLabel(access), color = Color(0xFF78A9FF))
                        if (permissionStore.get(name) == access) Icon(Icons.Default.Check, null, tint = Color(0xFF78A9FF))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { toolDetail = null }) { Text("Закрыть") } })
    }

    if (showModels) ModalBottomSheet(onDismissRequest = { showModels = false }, containerColor = KvasSurface) {
        Text("Выбор модели", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = KvasText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(modelSearch, { modelSearch = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = KvasText), decorationBox = { inner -> Box { if (modelSearch.isEmpty()) Text("Поиск по моделям", color = KvasDim); inner() } })
        if (providers.isEmpty()) Text("Пока нет подключённых провайдеров", Modifier.padding(horizontal = 20.dp), color = KvasMuted)
        providers.forEach { provider ->
            Text(provider.name, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = KvasMuted, fontWeight = FontWeight.Medium)
            provider.models.filter { it.id.contains(modelSearch, true) }.forEach { model ->
                ListItem(headlineContent = { Text(model.id, color = KvasText) }, supportingContent = { model.contextLimit?.let { Text("${it} токенов контекста", color = KvasDim) } },
                    modifier = Modifier.clickable { selectedProvider = provider; selectedModel = model; showModels = false; modelSearch = "" }, colors = ListItemDefaults.colors(containerColor = KvasSurface))
            }
        }
        TextButton(onClick = { showModels = false; showProviderForm = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить провайдера", color = KvasAccent) }
        Row(Modifier.fillMaxWidth().clickable { effortExpanded = true }.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Усилие", color = KvasText); Text(effort, color = KvasMuted)
            DropdownMenu(expanded = effortExpanded, onDismissRequest = { effortExpanded = false }) {
                listOf("Низкое", "Среднее", "Высокое", "Максимальное").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { effort = option; effortExpanded = false }) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showProviderForm) ModalBottomSheet(onDismissRequest = { showProviderForm = false }, containerColor = KvasSurface) {
        Text("Новый провайдер", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = KvasText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DarkInput("Имя провайдера", providerName) { providerName = it; checkedModels = emptyList() }
            DarkInput("Base URL", baseUrl) { baseUrl = it; checkedModels = emptyList() }
            DarkInput("API-ключ", apiKey, secret = true) { apiKey = it; checkedModels = emptyList() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    checkBusy = true; checkError = ""; checkedModels = emptyList()
                    scope.launch { try { checkedModels = api.fetchModels(baseUrl, apiKey) } catch (e: Exception) { checkError = e.message ?: "Проверка не пройдена." } finally { checkBusy = false } }
                }, enabled = providerName.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank() && !checkBusy, colors = ButtonDefaults.buttonColors(containerColor = KvasSurface2)) {
                    if (checkBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Проверить")
                }
                Text(if (checkedModels.isNotEmpty()) "Загружено моделей: ${checkedModels.size}" else checkError, color = if (checkError.isNotBlank()) Color(0xFFE4707A) else KvasMuted, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showProviderForm = false }) { Text("Отменить", color = KvasMuted) }
                Button(onClick = {
                    val p = ProviderConfig(UUID.randomUUID().toString(), providerName.trim(), baseUrl.trim(), apiKey, checkedModels)
                    providerStore.save(p); providers = providerStore.all(); selectedProvider = p; selectedModel = p.models.firstOrNull(); showProviderForm = false
                    providerName = ""; baseUrl = ""; apiKey = ""; checkedModels = emptyList()
                }, enabled = checkedModels.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = if (checkedModels.isNotEmpty()) KvasAccent else KvasSurface2, contentColor = if (checkedModels.isNotEmpty()) Color(0xFF18131F) else KvasDim)) { Text("Сохранить") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = KvasSurface) {
        Text("Настройки", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = KvasText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Системные инструкции", Modifier.padding(horizontal = 20.dp), color = KvasMuted)
        BasicTextField(systemInstructions, { systemInstructions = it }, Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 100.dp).clip(RoundedCornerShape(12.dp)).background(KvasSurface2).padding(12.dp), textStyle = androidx.compose.ui.text.TextStyle(color = KvasText))
        Text("Возможности", Modifier.padding(horizontal = 20.dp), color = KvasMuted)
        Row(Modifier.fillMaxWidth().clickable { webSearchEnabled = !webSearchEnabled }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Поиск в интернете", Modifier.weight(1f), color = KvasText); Switch(webSearchEnabled, { webSearchEnabled = it }) }
        Row(Modifier.fillMaxWidth().clickable { showSettings = false; showTools = true }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Разрешения инструментов", Modifier.weight(1f), color = KvasText); Icon(Icons.Default.ChevronRight, null, tint = KvasMuted) }
        Row(Modifier.fillMaxWidth().clickable {
            showSettings = false
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }.onFailure {
                Toast.makeText(context, "Не удалось открыть настройки уведомлений.", Toast.LENGTH_SHORT).show()
            }
        }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Уведомления Android", Modifier.weight(1f), color = KvasText)
            Icon(Icons.Default.Notifications, null, tint = KvasMuted)
        }
        Button(onClick = { context.getSharedPreferences("kvas_settings", 0).edit().putString("instructions", systemInstructions).apply(); showSettings = false }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) { Text("Сохранить настройки") }
        Spacer(Modifier.height(20.dp))
    }

    if (showContext) AlertDialog(onDismissRequest = { showContext = false }, title = { Text("Контекстное окно") }, text = {
        val total = selectedModel?.contextLimit
        val used = lastUsage?.inputTokens ?: 0L
        val percent = if (total != null && total > 0) (used * 100 / total).coerceIn(0, 100) else 0
        Column {
            Text("$percent% использовано", color = KvasText)
            LinearProgressIndicator(progress = { if (total != null && total > 0) used.toFloat().div(total).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().padding(vertical = 10.dp))
            Text("${used} / ${total?.let { String.format("%,d", it) } ?: "—"} токенов", color = KvasMuted)
            if (total == null) Text("Провайдер не сообщил размер контекста этой модели.", Modifier.padding(top = 8.dp), color = KvasDim, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { showContext = false }) { Text("Закрыть") } })

    costInfo?.let { info -> AlertDialog(onDismissRequest = { costInfo = null }, title = { Text("Информация о чате") }, text = { Text(info) }, confirmButton = { TextButton(onClick = { costInfo = null }) { Text("Понятно") } }) }

    pendingToolApproval?.let { request ->
        AlertDialog(
            onDismissRequest = { resolveToolApproval(ApprovalChoice.DENY) },
            title = { Text("Квас запрашивает доступ к") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = KvasSurface2)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(request.title, color = KvasText, fontWeight = FontWeight.SemiBold)
                            Text(request.description, Modifier.padding(top = 5.dp), color = KvasMuted, fontSize = 12.sp)
                        }
                    }
                    Text("Квас использует это действие, чтобы продолжить работу над запросом.", color = KvasMuted, fontSize = 13.sp)
                    Text("Внимательно проверяйте действие. Разрешения можно изменить в любое время в настройках.", color = Color(0xFFE9B66D), fontSize = 11.sp)
                }
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { resolveToolApproval(ApprovalChoice.ALLOW_ONCE) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Разрешить один раз") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { resolveToolApproval(ApprovalChoice.DENY) }) { Text("Отклонить") }
                        TextButton(onClick = { resolveToolApproval(ApprovalChoice.ALWAYS_ALLOW) }) { Text("Всегда разрешать") }
                    }
                }
            }
        )
    }

    pendingUndo?.let { change ->
        AlertDialog(
            onDismissRequest = { pendingUndo = null },
            title = { Text("Откатить последнее изменение?") },
            text = { Text("Вернуть файл ${change.path} к состоянию до последней правки? Если файл изменился после неё, Квас откажется его перезаписывать.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        runCatching { createRuntime().undoFileChange(workingDirectory, change) }
                            .onSuccess {
                                fileChanges = fileChanges.dropLast(1)
                                Toast.makeText(context, "Изменение отменено.", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                Toast.makeText(context, it.message ?: "Не удалось отменить изменение.", Toast.LENGTH_LONG).show()
                            }
                        pendingUndo = null
                    }
                }) { Text("Откатить") }
            },
            dismissButton = { TextButton(onClick = { pendingUndo = null }) { Text("Отмена") } }
        )
    }

    taskToStop?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToStop = null },
            title = { Text("Остановить фоновую задачу?") },
            text = { Text("Точно закрыть «${task.description}»?") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        runCatching { createRuntime().stopBackgroundTask(workingDirectory, task.id) }
                            .onFailure { Toast.makeText(context, it.message ?: "Не удалось остановить процесс.", Toast.LENGTH_LONG).show() }
                        taskToStop = null
                    }
                }) { Text("Остановить") }
            },
            dismissButton = { TextButton(onClick = { taskToStop = null }) { Text("Отмена") } }
        )
    }

    fileToView?.let { file ->
        AgentFileViewer(
            file = file,
            onClose = { fileToView = null },
            onCopy = {
                val content = file.textContent
                if (content != null) clipboard.setText(AnnotatedString(content))
                else Toast.makeText(context, "Бинарное содержимое нельзя скопировать как текст.", Toast.LENGTH_SHORT).show()
            },
            onDownload = { fileDownloadPicker.launch(file.name) },
            onSaveMarkdown = { fileMarkdownPicker.launch("${file.name.substringBeforeLast('.', file.name)}.md") },
            onSavePdf = { filePdfPicker.launch("${file.name.substringBeforeLast('.', file.name)}.pdf") }
        )
    }
}

private fun accessLabel(access: ToolAccess): String = when (access) {
    ToolAccess.ASK -> "Спрашивать"
    ToolAccess.ALWAYS_ALLOW -> "Всегда разрешать"
    ToolAccess.NEVER -> "Никогда"
}

private fun resolveDisplayName(context: Context, uri: Uri, fallback: String): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else fallback
        } ?: fallback
    }.getOrDefault(fallback)

private suspend fun readTextAttachment(context: Context, uri: Uri, name: String): String? =
    withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val textLike = mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
            name.substringAfterLast('.', "").lowercase() in setOf(
                "md", "txt", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "html", "css",
                "xml", "json", "yaml", "yml", "toml", "gradle", "sh", "sql", "log", "csv"
            )
        if (!textLike) return@withContext null
        val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
        stream.use { String(readBounded(it, 128 * 1024), Charsets.UTF_8) }
    }

private suspend fun loadImageDataUri(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val stream = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть выбранное изображение.")
        val bytes = stream.use { readBounded(it, 5 * 1024 * 1024) }
        "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= limit) { "Файл превышает допустимый размер ${limit / 1024} КБ." }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun createSimplePdf(source: String): ByteArray {
    val text = source.take(100_000)
    val lines = text.lines().flatMap { line ->
        if (line.isEmpty()) listOf("") else line.chunked(88)
    }.take(5_000)
    val document = android.graphics.pdf.PdfDocument()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = 10f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
    }
    val pageWidth = 595
    val pageHeight = 842
    val margin = 38f
    val lineHeight = 14f
    var pageNumber = 1
    var index = 0
    while (index < lines.size || (lines.isEmpty() && pageNumber == 1)) {
        val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var y = margin
        var count = 0
        while (index < lines.size && y < pageHeight - margin) {
            page.canvas.drawText(lines[index], margin, y, paint)
            index++
            y += lineHeight
            count++
        }
        if (count == 0 && lines.isEmpty()) page.canvas.drawText("Пустой файл", margin, y, paint)
        document.finishPage(page)
        pageNumber++
        if (lines.isEmpty()) break
    }
    val output = java.io.ByteArrayOutputStream()
    document.writeTo(output)
    document.close()
    return output.toByteArray()
}

@Composable
private fun ContextRing(used: Long, total: Long?, onClick: () -> Unit) {
    val progress = if (total != null && total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(Modifier.size(34.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize().padding(5.dp), color = KvasAccent, trackColor = KvasSurface2, strokeWidth = 2.dp)
        Text(if (total == null) "?" else "${(progress * 100).toInt()}", color = KvasDim, fontSize = 8.sp)
    }
}

@Composable
private fun AttachmentCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = KvasSurface2), border = BorderStroke(1.dp, KvasBorder), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, tint = KvasAccent); Spacer(Modifier.height(8.dp)); Text(title, color = KvasText) }
    }
}

@Composable
private fun DarkInput(label: String, value: String, secret: Boolean = false, onValueChange: (String) -> Unit) {
    Column {
        Text(label, color = KvasMuted, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        BasicTextField(value, onValueChange, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KvasSurface2).padding(12.dp), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = KvasText, fontSize = 14.sp), visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    }
}
