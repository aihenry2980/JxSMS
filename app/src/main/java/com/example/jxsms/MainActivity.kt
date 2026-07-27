package com.example.jxsms

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.provider.Settings
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.data.preferences.DefaultTagColors
import com.example.jxsms.data.trash.TrashResult
import com.example.jxsms.data.trash.TrashSmsEntity
import com.example.jxsms.domain.model.SmsCategory
import com.example.jxsms.domain.model.SwipeAction
import com.example.jxsms.notification.SmsNotificationManager
import com.example.jxsms.role.DefaultSmsRoleManager
import com.example.jxsms.ui.AppViewModel
import com.example.jxsms.ui.theme.JxSMSTheme
import com.example.jxsms.util.AvatarColorGenerator
import com.example.jxsms.util.PhoneNumberNormalizer
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

private enum class Screen { INBOX, DETAIL, CONVERSATION, TRASH, SETTINGS, UNSUPPORTED }
private val LocalTagColors = staticCompositionLocalOf { DefaultTagColors }

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JxSMSTheme {
                JxApp(viewModel, intent, onRoleChanged = { recreate() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JxApp(vm: AppViewModel, launchIntent: Intent, onRoleChanged: () -> Unit) {
    val context = LocalContext.current
    val role = remember { DefaultSmsRoleManager(context) }
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    var isDefault by remember { mutableStateOf(role.isDefault()) }
    var screen by rememberSaveable {
        mutableStateOf(if (launchIntent.action == Intent.ACTION_SENDTO) Screen.UNSUPPORTED else Screen.INBOX)
    }
    var selectedId by rememberSaveable {
        mutableStateOf(launchIntent.getLongExtra(SmsNotificationManager.EXTRA_SMS_ID, -1L))
    }
    var selectedSender by rememberSaveable { mutableStateOf("") }
    var detailReturnScreen by rememberSaveable { mutableStateOf(Screen.INBOX) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = role.isDefault()
        if (isDefault) onRoleChanged()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isDefault = role.isDefault() }
    BackHandler(enabled = screen != Screen.INBOX && screen != Screen.UNSUPPORTED) {
        when (screen) {
            Screen.DETAIL -> {
                selectedId = -1L
                screen = detailReturnScreen
            }
            Screen.CONVERSATION -> {
                selectedSender = ""
                screen = Screen.INBOX
            }
            Screen.TRASH, Screen.SETTINGS -> screen = Screen.INBOX
            else -> Unit
        }
    }
    if (screen == Screen.UNSUPPORTED) {
        UnsupportedSendScreen()
        return
    }
    if (!prefs.riskAccepted) {
        Onboarding(onRequest = {
                vm.setRiskAccepted(true)
                roleLauncher.launch(role.requestIntent())
            })
        return
    }
    LaunchedEffect(isDefault) {
        if (isDefault) permissions.launch(arrayOf(
            Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS
        ))
    }
    if (selectedId > 0 && screen == Screen.INBOX) screen = Screen.DETAIL
    CompositionLocalProvider(LocalTagColors provides prefs.tagColors) {
        when (screen) {
            Screen.INBOX -> InboxScreen(vm, isDefault,
                onTrash = { screen = Screen.TRASH }, onSettings = { screen = Screen.SETTINGS },
                onOpen = { selectedId = it; detailReturnScreen = Screen.INBOX; screen = Screen.DETAIL },
                onConversation = { selectedSender = it; screen = Screen.CONVERSATION })
            Screen.DETAIL -> DetailScreen(vm, selectedId, isDefault,
                onBack = { selectedId = -1; screen = detailReturnScreen })
            Screen.CONVERSATION -> ConversationScreen(vm, selectedSender, isDefault,
                onBack = { selectedSender = ""; screen = Screen.INBOX },
                onOpen = {
                    selectedId = it
                    detailReturnScreen = Screen.CONVERSATION
                    screen = Screen.DETAIL
                })
            Screen.TRASH -> TrashScreen(vm, isDefault, onBack = { screen = Screen.INBOX })
            Screen.SETTINGS -> SettingsScreen(vm, isDefault, onBack = { screen = Screen.INBOX },
                requestRole = { roleLauncher.launch(role.requestIntent()) })
            Screen.UNSUPPORTED -> UnsupportedSendScreen()
        }
    }
}

@Composable
private fun Onboarding(onRequest: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("JX SMS Reader", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("为了让通知栏删除、垃圾箱和恢复功能正常工作，JX 必须成为系统默认短信应用。")
        Spacer(Modifier.height(12.dp))
        Text("重要：当前版本只支持普通 SMS，不支持 MMS、群组彩信或 RCS。设为默认后，这些消息可能无法正常显示。",
            color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange = { checked = it })
            Text("我已了解并接受 MMS/RCS 风险", Modifier.clickable { checked = !checked })
        }
        Button(onClick = onRequest, enabled = checked, modifier = Modifier.fillMaxWidth()) {
            Text("设为默认短信应用")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxScreen(
    vm: AppViewModel, isDefault: Boolean, onTrash: () -> Unit,
    onSettings: () -> Unit, onOpen: (Long) -> Unit, onConversation: (String) -> Unit
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val conversationMessages by vm.conversationMessages.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val displayedMessages = if (prefs.mergeConversations) conversationMessages else messages
    val filteredMessages = remember(displayedMessages, query) {
        val term = query.trim()
        if (term.isEmpty()) displayedMessages else displayedMessages.filter { sms ->
            sms.address.contains(term, ignoreCase = true) ||
                sms.contactName.orEmpty().contains(term, ignoreCase = true) ||
                sms.body.contains(term, ignoreCase = true) ||
                sms.category.label.contains(term, ignoreCase = true)
        }
    }
    val conversations = remember(filteredMessages) {
        filteredMessages.groupBy { PhoneNumberNormalizer.normalize(it.address) }
            .map { (senderKey, values) -> ConversationGroup(senderKey, values.first(), values.size) }
            .sortedByDescending { it.latest.date }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("短信") }, actions = {
            TextButton(onClick = onTrash) { Text("垃圾箱") }
            TextButton(onClick = onSettings) { Text("设置") }
        }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!isDefault) Text("受限只读模式：JX 当前不是默认短信应用，删除和恢复已禁用。",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("搜索短信") },
                placeholder = { Text("发信者、正文或分类") }
            )
            if (filteredMessages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "没有可显示的短信" else "没有匹配的短信")
            } else if (prefs.mergeConversations) {
                LazyColumn {
                    items(conversations, key = { it.senderKey }) { conversation ->
                        ConversationSummaryRow(conversation) { onConversation(conversation.senderKey) }
                    }
                }
            } else {
                LazyColumn {
                    items(filteredMessages, key = { it.id }) { sms ->
                        SwipeRow(sms, prefs.left, prefs.right, enabled = isDefault,
                            onOpen = { onOpen(sms.id) },
                            onAction = { action ->
                                when (action) {
                                    SwipeAction.DELETE -> scope.launch {
                                        when (val result = vm.delete(sms.id)) {
                                            is TrashResult.Success -> {
                                                val undo = snack.showSnackbar("已移入垃圾箱", "撤销")
                                                if (undo == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                                    if (!vm.restore(result.trashId)) snack.showSnackbar("恢复失败")
                                                }
                                            }
                                            is TrashResult.Failure -> snack.showSnackbar(result.message)
                                        }
                                    }
                                    SwipeAction.MARK_READ_UNREAD -> vm.markRead(sms.id, !sms.read)
                                    SwipeAction.COPY_TEXT -> {
                                        copyText(context, sms.body)
                                        scope.launch { snack.showSnackbar("已复制") }
                                    }
                                    SwipeAction.NONE -> Unit
                                }
                            })
                    }
                }
            }
        }
    }
}

private data class ConversationGroup(
    val senderKey: String,
    val latest: SmsMessageModel,
    val count: Int
)

@Composable
private fun ConversationSummaryRow(group: ConversationGroup, onOpen: () -> Unit) {
    val sms = group.latest
    Column {
        Row(Modifier.fillMaxWidth().background(categoryRowBackground(sms.category))
            .clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Avatar(sms.contactName ?: sms.address, sms.contactPhotoUri)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(sms.contactName ?: sms.address, Modifier.weight(1f),
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CategoryTag(sms.category)
                    Spacer(Modifier.width(7.dp))
                    Text("${group.count} 条", style = MaterialTheme.typography.labelMedium)
                }
                Text(if (sms.isOutgoing()) "我：${sms.body}" else sms.body,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal)
            }
        }
        WavySmsDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(
    sms: SmsMessageModel, left: SwipeAction, right: SwipeAction, enabled: Boolean,
    onOpen: () -> Unit, onAction: (SwipeAction) -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            val action = if (it == SwipeToDismissBoxValue.EndToStart) left else right
            if (it != SwipeToDismissBoxValue.Settled && enabled && action != SwipeAction.NONE) {
                onAction(action); false
            } else false
        }
    )
    SwipeToDismissBox(state = state, enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            val action = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) left else right
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(20.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    Alignment.CenterEnd else Alignment.CenterStart) { Text(action.label) }
        }) { SmsRow(sms, onOpen) }
}

@Composable
private fun SmsRow(sms: SmsMessageModel, onOpen: () -> Unit) {
    val rowBackground = categoryRowBackground(sms.category)
    Column {
        Row(Modifier.fillMaxWidth()
            .background(rowBackground)
            .clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Avatar(sms.contactName ?: sms.address, sms.contactPhotoUri)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(sms.contactName ?: sms.address, Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CategoryTag(sms.category)
                    Spacer(Modifier.width(7.dp))
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(sms.date)),
                        style = MaterialTheme.typography.labelSmall)
                }
                Text(if (sms.isOutgoing()) "我：${sms.body}" else sms.body,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal)
            }
        }
        WavySmsDivider()
    }
}

@Composable
private fun WavySmsDivider() {
    val lineColor = if (isSystemInDarkTheme()) Color(0xFF9AA3AE) else Color(0xFF59636F)
    val bandColor = MaterialTheme.colorScheme.surface
    Canvas(Modifier.fillMaxWidth().height(12.dp).background(bandColor)) {
        val amplitude = size.height * 0.32f
        val centerY = size.height / 2f
        val waveWidth = 30.dp.toPx()
        val path = Path().apply {
            moveTo(0f, centerY)
            var x = 0f
            while (x < size.width) {
                quadraticTo(x + waveWidth / 4f, centerY - amplitude, x + waveWidth / 2f, centerY)
                quadraticTo(x + waveWidth * 3f / 4f, centerY + amplitude, x + waveWidth, centerY)
                x += waveWidth
            }
        }
        drawPath(path, color = lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2.25.dp.toPx()))
    }
}

@Composable
private fun categoryRowBackground(category: SmsCategory): Color {
    val tagColor = Color(LocalTagColors.current.getValue(category))
    return if (isSystemInDarkTheme()) lerp(tagColor, Color.Black, 0.68f)
    else lerp(tagColor, Color.White, 0.89f)
}

@Composable
private fun Avatar(label: String, photoUri: Uri? = null) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, photoUri) {
        value = if (photoUri == null) null else withContext(Dispatchers.IO) {
            AvatarBitmapCache.get(photoUri) ?: runCatching {
                context.contentResolver.openInputStream(photoUri)?.use(BitmapFactory::decodeStream)
                    ?.also { AvatarBitmapCache.put(photoUri, it) }
            }.getOrNull()
        }
    }
    val bgInt = AvatarColorGenerator.colorFor(label)
    val bg = Color(bgInt)
    val fg = Color(AvatarColorGenerator.foregroundFor(bgInt))
    Box(Modifier.size(44.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize())
        else Text(AvatarColorGenerator.initials(label), color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CategoryTag(category: SmsCategory, onClick: (() -> Unit)? = null) {
    val color = Color(LocalTagColors.current.getValue(category))
    var modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color)
        .padding(horizontal = 7.dp, vertical = 2.dp)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Box(modifier) {
        Text(category.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    vm: AppViewModel,
    senderKey: String,
    canModify: Boolean,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit
) {
    val allMessages by vm.conversationMessages.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val messages = remember(allMessages, senderKey) {
        allMessages.filter { PhoneNumberNormalizer.normalize(it.address) == senderKey }
    }
    val title = messages.firstOrNull()?.let { it.contactName ?: it.address } ?: "会话"
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该会话没有短信")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(messages, key = { it.id }) { sms ->
                    SwipeRow(sms, prefs.left, prefs.right, enabled = canModify,
                        onOpen = { onOpen(sms.id) },
                        onAction = { action ->
                            when (action) {
                                SwipeAction.DELETE -> scope.launch {
                                    when (val result = vm.delete(sms.id)) {
                                        is TrashResult.Success -> {
                                            val undo = snack.showSnackbar("已移入垃圾箱", "撤销")
                                            if (undo == androidx.compose.material3.SnackbarResult.ActionPerformed &&
                                                !vm.restore(result.trashId)) {
                                                snack.showSnackbar("恢复失败")
                                            }
                                        }
                                        is TrashResult.Failure -> snack.showSnackbar(result.message)
                                    }
                                }
                                SwipeAction.MARK_READ_UNREAD -> vm.markRead(sms.id, !sms.read)
                                SwipeAction.COPY_TEXT -> {
                                    copyText(context, sms.body)
                                    scope.launch { snack.showSnackbar("已复制") }
                                }
                                SwipeAction.NONE -> Unit
                            }
                        })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(vm: AppViewModel, id: Long, canModify: Boolean, onBack: () -> Unit) {
    val messages by vm.conversationMessages.collectAsStateWithLifecycle()
    val sms = messages.firstOrNull { it.id == id }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var categoryOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(id, canModify, sms?.type) {
        if (canModify && sms?.isOutgoing() == false) vm.markRead(id, true)
    }
    Scaffold(topBar = { TopAppBar(title = { Text("短信详情") },
        navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
        snackbarHost = { SnackbarHost(snack) }) { padding ->
        if (sms == null) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("短信不存在或已删除")
        } else Column(Modifier.padding(padding).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(sms.contactName ?: sms.address, sms.contactPhotoUri); Spacer(Modifier.width(12.dp))
                Column { Text(sms.contactName ?: sms.address, fontWeight = FontWeight.Bold); Text(sms.address) }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryTag(sms.category) { categoryOpen = true }
                Spacer(Modifier.width(8.dp))
                Text("点击标签可更改分类", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${if (sms.isOutgoing()) "发送" else "收到"}时间：${
                DateFormat.getDateTimeInstance().format(Date(sms.date))
            }")
            Text("SIM：${sms.subscriptionId?.toString() ?: "未知"} · ${
                if (sms.isOutgoing()) "已发送" else if (sms.read) "已读" else "未读"
            }")
            Spacer(Modifier.height(18.dp)); Text(sms.body, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(22.dp))
            Row {
                TextButton(onClick = { copyText(context, sms.body); scope.launch { snack.showSnackbar("已复制") } }) {
                    Text("复制正文")
                }
                TextButton(onClick = { vm.markRead(id, !sms.read) },
                    enabled = canModify && !sms.isOutgoing()) {
                    Text(if (sms.read) "标为未读" else "标为已读")
                }
                TextButton(onClick = { scope.launch { vm.delete(id); onBack() } }, enabled = canModify) {
                    Text("移入垃圾箱")
                }
            }
        }
    }
    if (categoryOpen && sms != null) {
        CategoryPickerDialog(
            current = sms.category,
            onDismiss = { categoryOpen = false },
            onSelect = {
                vm.setCategory(id, it)
                categoryOpen = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashScreen(vm: AppViewModel, canModify: Boolean, onBack: () -> Unit) {
    val values by vm.trash.collectAsStateWithLifecycle()
    var clearConfirm by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("垃圾箱") },
        navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
        actions = { TextButton(onClick = { clearConfirm = true },
            enabled = canModify && values.isNotEmpty()) { Text("清空") } }) },
        snackbarHost = { SnackbarHost(snack) }) { padding ->
        if (values.isEmpty()) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("垃圾箱为空")
        } else LazyColumn(Modifier.padding(padding)) {
            items(values, key = { it.trashId }) { item ->
                TrashRow(item, canModify, onRestore = {
                    scope.launch { if (!vm.restore(item.trashId)) snack.showSnackbar("恢复失败，短信仍保留在垃圾箱") }
                }, onDelete = { deleteId = item.trashId })
            }
        }
    }
    if (clearConfirm) ConfirmDialog("清空垃圾箱？", "所有垃圾箱短信都会永久删除，操作无法撤销。",
        onDismiss = { clearConfirm = false }, onConfirm = { vm.clearTrash(); clearConfirm = false })
    if (deleteId != null) ConfirmDialog("永久删除？", "永久删除后无法恢复。",
        onDismiss = { deleteId = null }, onConfirm = { vm.permanentDelete(deleteId!!); deleteId = null })
}

@Composable
private fun TrashRow(item: TrashSmsEntity, canModify: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp)) {
        Avatar(item.contactName ?: item.address); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.contactName ?: item.address, fontWeight = FontWeight.Bold)
            CategoryTag(item.category)
            Text(item.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val days = ceil((item.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 86_400_000.0).toInt()
            Text("删除于 ${DateFormat.getDateInstance().format(Date(item.deletedAt))} · 剩余 $days 天",
                style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(onClick = onRestore, enabled = canModify) { Text("恢复") }
                TextButton(onClick = onDelete, enabled = canModify) { Text("永久删除") }
            }
        }
    }
    HorizontalDivider(thickness = 1.dp, color = Color.White)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: AppViewModel, isDefault: Boolean, onBack: () -> Unit, requestRole: () -> Unit) {
    val context = LocalContext.current
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    var colorCategory by rememberSaveable { mutableStateOf<SmsCategory?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("设置") },
        navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            item {
                SettingLine("默认短信应用", if (isDefault) "已设为默认" else "未设为默认")
                Button(onClick = requestRole, enabled = !isDefault) { Text("请求成为默认短信应用") }
                val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                    PackageManager.PERMISSION_GRANTED
                SettingLine("联系人权限", if (contacts) "已授权" else "未授权")
                TextButton(onClick = { openAppSettings(context) }) { Text("联系人权限设置") }
                val notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                SettingLine("通知权限", if (notifications) "已授权" else "未授权")
                TextButton(onClick = { openNotificationSettings(context) }) { Text("通知权限设置") }
                SettingSwitch(
                    "通知删除按钮在左侧",
                    if (prefs.notificationDeleteOnLeft) "左：🔴 删除　右：✓ 标为已读"
                    else "左：✓ 标为已读　右：🔴 删除",
                    prefs.notificationDeleteOnLeft,
                    vm::setNotificationDeleteOnLeft
                )
                SettingSwitch(
                    "合并同一发信者",
                    if (prefs.mergeConversations) "Inbox 按发信者合并为会话" else "每条 SMS 独立显示",
                    prefs.mergeConversations,
                    vm::setMergeConversations
                )
                SwipeSelector("左滑动作", prefs.left, vm::setLeft)
                SwipeSelector("右滑动作", prefs.right, vm::setRight)
                SettingLine("垃圾箱", "短信保留 30 天，每日自动清理")
                SettingLine("短信分类", "使用离线、确定性的中韩英关键词规则")
                Text("标签颜色", fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                SmsCategory.entries.forEach { category ->
                    TagColorSetting(category, prefs.tagColors.getValue(category)) {
                        colorCategory = category
                    }
                }
                SettingLine("MMS 与 RCS", "当前不支持 MMS、群组彩信和 RCS")
                SettingLine("版本", "JX SMS Reader 1.0")
            }
        }
    }
    colorCategory?.let { category ->
        TagColorPickerDialog(
            category = category,
            current = prefs.tagColors.getValue(category),
            onDismiss = { colorCategory = null },
            onSelect = {
                vm.setTagColor(category, it)
                colorCategory = null
            }
        )
    }
}

private val TagColorPalette = listOf(
    "深蓝" to 0xFF245A9A,
    "靛蓝" to 0xFF3F51A3,
    "紫色" to 0xFF60458F,
    "玫红" to 0xFF9A365F,
    "红色" to 0xFF9B3434,
    "橙棕" to 0xFF9A4A20,
    "金棕" to 0xFF80620E,
    "绿色" to 0xFF276A43,
    "青色" to 0xFF147078,
    "深灰" to 0xFF515A66
)

@Composable
private fun TagColorSetting(category: SmsCategory, color: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryTag(category)
        Spacer(Modifier.width(12.dp))
        Text(category.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(color)))
        Spacer(Modifier.width(8.dp))
        Text("更改", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TagColorPickerDialog(
    category: SmsCategory,
    current: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择“${category.label}”的颜色") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(TagColorPalette, key = { it.second }) { (name, color) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(color) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(color)))
                        Spacer(Modifier.width(12.dp))
                        Text(name, Modifier.weight(1f))
                        if (current == color) Text("当前", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CategoryPickerDialog(
    current: SmsCategory,
    onDismiss: () -> Unit,
    onSelect: (SmsCategory) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更改短信分类") },
        text = {
            Column {
                SmsCategory.entries.forEach { category ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(category) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CategoryTag(category)
                        Spacer(Modifier.width(12.dp))
                        Text(category.label, Modifier.weight(1f))
                        if (category == current) Text("当前", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SwipeSelector(title: String, value: SwipeAction, onChange: (SwipeAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp)) {
        Text(title, Modifier.weight(1f)); Text(value.label)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            SwipeAction.entries.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, onClick = { onChange(action); open = false })
            }
        }
    }
}

@Composable private fun SettingLine(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun UnsupportedSendScreen() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("JX SMS Reader 不支持发送短信。", style = MaterialTheme.typography.headlineSmall)
    }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("短信正文", text))
}
private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")))
}
private fun openNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

private fun SmsMessageModel.isOutgoing(): Boolean =
    type == Telephony.Sms.MESSAGE_TYPE_SENT

private object AvatarBitmapCache {
    private val cache = LruCache<String, android.graphics.Bitmap>(32)
    fun get(uri: Uri) = cache.get(uri.toString())
    fun put(uri: Uri, bitmap: android.graphics.Bitmap) = cache.put(uri.toString(), bitmap)
}
