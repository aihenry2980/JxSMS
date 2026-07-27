package com.example.jxsms

import android.Manifest
import android.app.NotificationManager
import android.app.LocaleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.provider.Telephony
import android.provider.Settings
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.annotation.StringRes
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
import com.example.jxsms.ui.emailbackup.EmailBackupHistoryScreen
import com.example.jxsms.ui.emailbackup.EmailBackupScreen
import com.example.jxsms.ui.emailbackup.EmailBackupViewModel
import com.example.jxsms.ui.theme.JxSMSTheme
import com.example.jxsms.util.AvatarColorGenerator
import com.example.jxsms.util.PhoneNumberNormalizer
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil

private enum class Screen {
    INBOX, OTP, DETAIL, CONVERSATION, TRASH, SETTINGS,
    EMAIL_BACKUP, EMAIL_BACKUP_HISTORY, UNSUPPORTED
}
private val LocalTagColors = staticCompositionLocalOf { DefaultTagColors }

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()
    private val emailBackupViewModel by viewModels<EmailBackupViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JxSMSTheme {
                JxApp(viewModel, emailBackupViewModel, intent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JxApp(
    vm: AppViewModel,
    emailBackupVm: EmailBackupViewModel,
    launchIntent: Intent
) {
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
    val mergedInboxListState = rememberLazyListState()
    val unmergedInboxListState = rememberLazyListState()
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
            Screen.OTP, Screen.TRASH, Screen.SETTINGS -> screen = Screen.INBOX
            Screen.EMAIL_BACKUP -> screen = Screen.SETTINGS
            Screen.EMAIL_BACKUP_HISTORY -> screen = Screen.EMAIL_BACKUP
            else -> Unit
        }
    }
    if (screen == Screen.UNSUPPORTED) {
        UnsupportedSendScreen()
        return
    }
    LaunchedEffect(Unit) {
        permissions.launch(arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS
        ))
    }
    if (selectedId > 0 && screen == Screen.INBOX) screen = Screen.DETAIL
    CompositionLocalProvider(LocalTagColors provides prefs.tagColors) {
        when (screen) {
            Screen.INBOX -> InboxScreen(vm, isDefault,
                mergedListState = mergedInboxListState,
                unmergedListState = unmergedInboxListState,
                onOtp = { screen = Screen.OTP },
                onTrash = { screen = Screen.TRASH }, onSettings = { screen = Screen.SETTINGS },
                onOpen = { selectedId = it; detailReturnScreen = Screen.INBOX; screen = Screen.DETAIL },
                onConversation = { selectedSender = it; screen = Screen.CONVERSATION })
            Screen.OTP -> OtpScreen(vm, isDefault, onBack = { screen = Screen.INBOX })
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
                emailBackupVm = emailBackupVm,
                onCreateBackup = { screen = Screen.EMAIL_BACKUP },
                onBackupHistory = { screen = Screen.EMAIL_BACKUP_HISTORY })
            Screen.EMAIL_BACKUP -> EmailBackupScreen(
                emailBackupVm,
                onBack = { screen = Screen.SETTINGS },
                onHistory = { screen = Screen.EMAIL_BACKUP_HISTORY }
            )
            Screen.EMAIL_BACKUP_HISTORY -> EmailBackupHistoryScreen(
                emailBackupVm,
                onBack = { screen = Screen.EMAIL_BACKUP },
                onRegenerate = { screen = Screen.EMAIL_BACKUP }
            )
            Screen.UNSUPPORTED -> UnsupportedSendScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxScreen(
    vm: AppViewModel, isDefault: Boolean, onTrash: () -> Unit,
    mergedListState: LazyListState,
    unmergedListState: LazyListState,
    onOtp: () -> Unit, onSettings: () -> Unit,
    onOpen: (Long) -> Unit, onConversation: (String) -> Unit
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val conversationMessages by vm.conversationMessages.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val copiedText = stringResource(R.string.copied)
    val movedText = stringResource(R.string.moved_to_trash)
    val undoText = stringResource(R.string.undo)
    val restoreFailedText = stringResource(R.string.restore_failed)
    val deleteFailedText = stringResource(R.string.delete_failed)
    var query by rememberSaveable { mutableStateOf("") }
    var deleteConversation by remember { mutableStateOf<ConversationGroup?>(null) }
    val displayedMessages = if (prefs.mergeConversations) conversationMessages else messages
    val filteredMessages = remember(displayedMessages, query) {
        val term = query.trim()
        if (term.isEmpty()) displayedMessages else displayedMessages.filter { sms ->
            sms.address.contains(term, ignoreCase = true) ||
                sms.contactName.orEmpty().contains(term, ignoreCase = true) ||
                sms.body.contains(term, ignoreCase = true) ||
                resources.getString(categoryStringRes(sms.category)).contains(term, ignoreCase = true)
        }
    }
    val conversations = remember(filteredMessages) {
        filteredMessages.groupBy { PhoneNumberNormalizer.normalize(it.address) }
            .map { (senderKey, values) -> ConversationGroup(senderKey, values) }
            .sortedByDescending { it.latest.date }
    }
    val searchBackground = if (isSystemInDarkTheme()) Color(0xFF3A3D42) else Color(0xFFE5E7EB)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.inbox_title)) }, actions = {
            CategoryTag(SmsCategory.OTP, onClick = onOtp)
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.width(112.dp).height(48.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = {
                    Text(
                        stringResource(R.string.search_sms),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = searchBackground,
                    unfocusedContainerColor = searchBackground,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )
            TextButton(onClick = onTrash) { Text(stringResource(R.string.trash_title)) }
            TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) }
        }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!isDefault) Text(stringResource(R.string.restricted_mode),
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp))
            if (filteredMessages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(if (query.isBlank()) R.string.no_messages else R.string.no_matches))
            } else if (prefs.mergeConversations) {
                ScrollableSmsList(mergedListState) {
                    LazyColumn(state = mergedListState, modifier = Modifier.fillMaxSize()) {
                        items(conversations, key = { it.senderKey }) { conversation ->
                            SwipeConversationRow(
                                group = conversation,
                                enabled = isDefault,
                                onOpen = { onConversation(conversation.senderKey) },
                                onDeleteRequested = { deleteConversation = conversation }
                            )
                        }
                    }
                }
            } else {
                ScrollableSmsList(unmergedListState) {
                    LazyColumn(state = unmergedListState, modifier = Modifier.fillMaxSize()) {
                        items(filteredMessages, key = { it.id }) { sms ->
                            SwipeRow(sms, prefs.left, prefs.right, enabled = isDefault,
                                onOpen = { onOpen(sms.id) },
                                onAction = { action ->
                                    when (action) {
                                        SwipeAction.DELETE -> scope.launch {
                                            when (val result = vm.delete(sms.id)) {
                                                is TrashResult.Success -> {
                                                    val undo = snack.showSnackbar(movedText, undoText)
                                                    if (undo == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                                        if (!vm.restore(result.trashId)) snack.showSnackbar(restoreFailedText)
                                                    }
                                                }
                                                is TrashResult.Failure -> snack.showSnackbar(deleteFailedText)
                                            }
                                        }
                                        SwipeAction.MARK_READ_UNREAD -> vm.markRead(sms.id, !sms.read)
                                        SwipeAction.COPY_TEXT -> {
                                            copyText(context, sms.body)
                                            scope.launch { snack.showSnackbar(copiedText) }
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
    deleteConversation?.let { conversation ->
        ConfirmDialog(
            stringResource(R.string.delete_conversation_title),
            stringResource(R.string.delete_conversation_body, conversation.displayName, conversation.count),
            onDismiss = { deleteConversation = null },
            onConfirm = {
                deleteConversation = null
                scope.launch {
                    val trashIds = mutableListOf<Long>()
                    var failed = 0
                    conversation.messages.forEach { sms ->
                        when (val result = vm.delete(sms.id)) {
                            is TrashResult.Success -> trashIds += result.trashId
                            is TrashResult.Failure -> failed++
                        }
                    }
                    val message = if (failed == 0) {
                        resources.getString(R.string.bulk_delete_success, trashIds.size)
                    } else {
                        resources.getString(R.string.bulk_delete_partial, trashIds.size, failed)
                    }
                    val undo = snack.showSnackbar(message, if (trashIds.isNotEmpty()) undoText else null)
                    if (undo == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        trashIds.forEach { vm.restore(it) }
                    }
                }
            }
        )
    }
}

private data class ConversationGroup(
    val senderKey: String,
    val messages: List<SmsMessageModel>
) {
    val latest: SmsMessageModel get() = messages.first()
    val count: Int get() = messages.size
    val displayName: String get() = latest.contactName ?: latest.address
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeConversationRow(
    group: ConversationGroup,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    var rowWidth by remember { mutableStateOf(0) }
    val stateHolder = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.40f },
        confirmValueChange = {
            val crossedThreshold = rowWidth > 0 &&
                abs(stateHolder[0]?.requireOffset() ?: 0f) >= rowWidth * 0.40f
            if (it != SwipeToDismissBoxValue.Settled && enabled && crossedThreshold) {
                onDeleteRequested()
            }
            false
        }
    )
    stateHolder[0] = state
    SwipeToDismissBox(
        modifier = Modifier.onSizeChanged { rowWidth = it.width },
        state = state,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 22.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Alignment.CenterEnd
                } else {
                    Alignment.CenterStart
                }
            ) {
                Text(stringResource(R.string.delete_count, group.count),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold)
            }
        }
    ) {
        ConversationSummaryRow(group, onOpen)
    }
}

@Composable
private fun ConversationSummaryRow(group: ConversationGroup, onOpen: () -> Unit) {
    val sms = group.latest
    val rowBackground = categoryRowBackground(sms.category)
    Column {
        Row(Modifier.fillMaxWidth().background(rowBackground)
            .clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Avatar(sms.contactName ?: sms.address, sms.contactPhotoUri)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(sms.contactName ?: sms.address, Modifier.weight(1f),
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CategoryTag(sms.category)
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.message_count, group.count),
                        style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (sms.isOutgoing()) stringResource(R.string.me_preview, sms.body) else sms.body,
                        modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal)
                    Spacer(Modifier.width(8.dp))
                    MessageDateTime(sms.date)
                }
            }
        }
        WavySmsDivider(rowBackground)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(
    sms: SmsMessageModel, left: SwipeAction, right: SwipeAction, enabled: Boolean,
    showOutgoingAvatar: Boolean = false,
    onOpen: () -> Unit, onAction: (SwipeAction) -> Unit
) {
    var rowWidth by remember { mutableStateOf(0) }
    val stateHolder = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.40f },
        confirmValueChange = {
            val action = if (it == SwipeToDismissBoxValue.EndToStart) left else right
            val crossedThreshold = rowWidth > 0 &&
                abs(stateHolder[0]?.requireOffset() ?: 0f) >= rowWidth * 0.40f
            if (it != SwipeToDismissBoxValue.Settled && enabled &&
                action != SwipeAction.NONE && crossedThreshold
            ) {
                onAction(action); false
            } else false
        }
    )
    stateHolder[0] = state
    SwipeToDismissBox(
        modifier = Modifier.onSizeChanged { rowWidth = it.width },
        state = state,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            val action = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) left else right
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(20.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(stringResource(swipeActionStringRes(action)))
                }
        }) { SmsRow(sms, onOpen, showOutgoingAvatar) }
}

@Composable
private fun SmsRow(sms: SmsMessageModel, onOpen: () -> Unit, showOutgoingAvatar: Boolean = false) {
    val rowBackground = categoryRowBackground(sms.category)
    Column {
        Row(Modifier.fillMaxWidth()
            .background(rowBackground)
            .clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (showOutgoingAvatar && sms.isOutgoing()) JxOutgoingAvatar()
            else Avatar(sms.contactName ?: sms.address, sms.contactPhotoUri)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(sms.contactName ?: sms.address, Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CategoryTag(sms.category)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (sms.isOutgoing()) stringResource(R.string.me_preview, sms.body) else sms.body,
                        modifier = Modifier.weight(1f),
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal)
                    Spacer(Modifier.width(8.dp))
                    MessageDateTime(sms.date)
                }
            }
        }
        WavySmsDivider(rowBackground)
    }
}

@Composable
private fun MessageDateTime(timestamp: Long) {
    Text(
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(timestamp)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

@Composable
private fun WavySmsDivider(backgroundColor: Color) {
    val lineColor = if (isSystemInDarkTheme()) Color(0xFF777E87) else Color(0xFFB5BBC3)
    Canvas(Modifier.fillMaxWidth().height(10.dp).background(backgroundColor)) {
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
private fun ScrollableSmsList(
    state: LazyListState,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        content()
        val layout = state.layoutInfo
        val totalItems = layout.totalItemsCount
        val visibleItems = layout.visibleItemsInfo.size
        if (totalItems > visibleItems && visibleItems > 0) {
            val availableSteps = (totalItems - visibleItems).coerceAtLeast(1)
            val scrollFraction =
                (state.firstVisibleItemIndex.toFloat() / availableSteps).coerceIn(0f, 1f)
            Canvas(
                Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(5.dp)
                    .padding(vertical = 4.dp)
            ) {
                val thumbHeight =
                    (size.height * visibleItems.toFloat() / totalItems).coerceAtLeast(28.dp.toPx())
                        .coerceAtMost(size.height)
                val thumbTop = (size.height - thumbHeight) * scrollFraction
                drawRoundRect(
                    color = Color(0xFF7C848E).copy(alpha = 0.65f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, thumbTop),
                    size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f)
                )
            }
        }
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
private fun JxOutgoingAvatar() {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFD9EEFF))
            .border(1.5.dp, Color(0xFFE0B23C), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("JX", color = Color(0xFFB77900), fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun CategoryTag(category: SmsCategory, onClick: (() -> Unit)? = null) {
    val color = Color(LocalTagColors.current.getValue(category))
    var modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color)
        .padding(horizontal = 7.dp, vertical = 2.dp)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Box(modifier) {
        Text(stringResource(categoryStringRes(category)), color = Color.White,
            style = MaterialTheme.typography.labelSmall)
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
    val title = messages.firstOrNull()?.let { it.contactName ?: it.address }
        ?: stringResource(R.string.conversation)
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val movedText = stringResource(R.string.moved_to_trash)
    val undoText = stringResource(R.string.undo)
    val restoreFailedText = stringResource(R.string.restore_failed)
    val deleteFailedText = stringResource(R.string.delete_failed)
    val copiedText = stringResource(R.string.copied)
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_conversation))
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(messages, key = { it.id }) { sms ->
                    SwipeRow(sms, prefs.left, prefs.right, enabled = canModify,
                        showOutgoingAvatar = true,
                        onOpen = { onOpen(sms.id) },
                        onAction = { action ->
                            when (action) {
                                SwipeAction.DELETE -> scope.launch {
                                    when (val result = vm.delete(sms.id)) {
                                        is TrashResult.Success -> {
                                            val undo = snack.showSnackbar(movedText, undoText)
                                            if (undo == androidx.compose.material3.SnackbarResult.ActionPerformed &&
                                                !vm.restore(result.trashId)) {
                                                snack.showSnackbar(restoreFailedText)
                                            }
                                        }
                                        is TrashResult.Failure -> snack.showSnackbar(deleteFailedText)
                                    }
                                }
                                SwipeAction.MARK_READ_UNREAD -> vm.markRead(sms.id, !sms.read)
                                SwipeAction.COPY_TEXT -> {
                                    copyText(context, sms.body)
                                    scope.launch { snack.showSnackbar(copiedText) }
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
private fun OtpScreen(
    vm: AppViewModel,
    canModify: Boolean,
    onBack: () -> Unit
) {
    val allMessages by vm.conversationMessages.collectAsStateWithLifecycle()
    val otpMessages = remember(allMessages) {
        allMessages.filter { it.category == SmsCategory.OTP }
    }
    val listState = rememberLazyListState()
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val undoText = stringResource(R.string.undo)
    val restoreFailedText = stringResource(R.string.restore_failed)

    LaunchedEffect(otpMessages) {
        val availableIds = otpMessages.mapTo(mutableSetOf()) { it.id }
        selectedIds = selectedIds.intersect(availableIds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.otp_inbox)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        if (otpMessages.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.otp_empty))
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.selected_count, selectedIds.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge
                    )
                    TextButton(
                        onClick = {
                            selectedIds = if (selectedIds.size == otpMessages.size) {
                                emptySet()
                            } else {
                                otpMessages.mapTo(mutableSetOf()) { it.id }
                            }
                        },
                        enabled = canModify
                    ) {
                        Text(stringResource(
                            if (selectedIds.size == otpMessages.size) {
                                R.string.clear_selection
                            } else {
                                R.string.select_all
                            }
                        ))
                    }
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = canModify && selectedIds.isNotEmpty()
                    ) {
                        Text(
                            stringResource(R.string.delete_selected, selectedIds.size),
                            color = if (canModify && selectedIds.isNotEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                }
                ScrollableSmsList(listState) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(otpMessages, key = { it.id }) { sms ->
                            SelectableOtpRow(
                                sms = sms,
                                selected = sms.id in selectedIds,
                                enabled = canModify,
                                onToggle = {
                                    selectedIds = if (sms.id in selectedIds) {
                                        selectedIds - sms.id
                                    } else {
                                        selectedIds + sms.id
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_selected_title),
            body = stringResource(R.string.delete_selected_body, selectedIds.size),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val idsToDelete = selectedIds.toList()
                selectedIds = emptySet()
                scope.launch {
                    val trashIds = mutableListOf<Long>()
                    var failed = 0
                    idsToDelete.forEach { id ->
                        when (val result = vm.delete(id)) {
                            is TrashResult.Success -> trashIds += result.trashId
                            is TrashResult.Failure -> failed++
                        }
                    }
                    val message = if (failed == 0) {
                        resources.getString(R.string.bulk_delete_success, trashIds.size)
                    } else {
                        resources.getString(R.string.bulk_delete_partial, trashIds.size, failed)
                    }
                    val undo = snack.showSnackbar(
                        message,
                        actionLabel = if (trashIds.isNotEmpty()) undoText else null
                    )
                    if (undo == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        var restoreFailed = false
                        trashIds.forEach { trashId ->
                            if (!vm.restore(trashId)) restoreFailed = true
                        }
                        if (restoreFailed) snack.showSnackbar(restoreFailedText)
                    }
                }
            }
        )
    }
}

@Composable
private fun SelectableOtpRow(
    sms: SmsMessageModel,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val rowBackground = categoryRowBackground(sms.category)
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(rowBackground)
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                enabled = enabled
            )
            Spacer(Modifier.width(4.dp))
            Avatar(sms.contactName ?: sms.address, sms.contactPhotoUri)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sms.contactName ?: sms.address,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    MessageDateTime(sms.date)
                }
                Text(
                    sms.body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        WavySmsDivider(rowBackground)
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
    val copiedText = stringResource(R.string.copied)
    var categoryOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(id, canModify, sms?.type) {
        if (canModify && sms?.isOutgoing() == false) vm.markRead(id, true)
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.detail_title)) },
        navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }) },
        snackbarHost = { SnackbarHost(snack) }) { padding ->
        if (sms == null) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.message_missing))
        } else Column(Modifier.padding(padding).padding(18.dp)) {
            val headerBackground =
                if (isSystemInDarkTheme()) Color(0xFF34373C) else Color(0xFFE9EBEF)
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(headerBackground)
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(sms.contactName ?: sms.address, sms.contactPhotoUri)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(sms.contactName ?: sms.address, fontWeight = FontWeight.Bold)
                        Text(sms.address)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryTag(sms.category) { categoryOpen = true }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tap_tag_hint), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val formattedTime = DateFormat.getDateTimeInstance().format(Date(sms.date))
                Text(stringResource(
                    if (sms.isOutgoing()) R.string.sent_time else R.string.received_time,
                    formattedTime
                ))
                Text(stringResource(
                    R.string.sim_status,
                    sms.subscriptionId?.toString() ?: stringResource(R.string.unknown),
                    stringResource(
                        if (sms.isOutgoing()) R.string.sent else if (sms.read) R.string.read else R.string.unread
                    )
                ))
            }
            Spacer(Modifier.height(18.dp))
            SelectionContainer {
                Text(sms.body, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(22.dp))
            Row {
                TextButton(onClick = { copyText(context, sms.body); scope.launch { snack.showSnackbar(copiedText) } }) {
                    Text(stringResource(R.string.copy_body))
                }
                TextButton(onClick = { vm.markRead(id, !sms.read) },
                    enabled = canModify && !sms.isOutgoing()) {
                    Text(stringResource(if (sms.read) R.string.mark_unread else R.string.mark_read))
                }
                TextButton(onClick = { scope.launch { vm.delete(id); onBack() } }, enabled = canModify) {
                    Text(stringResource(R.string.move_to_trash))
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
    val restoreFailedKept = stringResource(R.string.restore_failed_kept)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.trash_title)) },
        navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
        actions = { TextButton(onClick = { clearConfirm = true },
            enabled = canModify && values.isNotEmpty()) { Text(stringResource(R.string.clear)) } }) },
        snackbarHost = { SnackbarHost(snack) }) { padding ->
        if (values.isEmpty()) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.trash_empty))
        } else LazyColumn(Modifier.padding(padding)) {
            items(values, key = { it.trashId }) { item ->
                TrashRow(item, canModify, onRestore = {
                    scope.launch { if (!vm.restore(item.trashId)) snack.showSnackbar(restoreFailedKept) }
                }, onDelete = { deleteId = item.trashId })
            }
        }
    }
    if (clearConfirm) ConfirmDialog(stringResource(R.string.clear_trash_title),
        stringResource(R.string.clear_trash_body),
        onDismiss = { clearConfirm = false }, onConfirm = { vm.clearTrash(); clearConfirm = false })
    if (deleteId != null) ConfirmDialog(stringResource(R.string.permanent_delete_title),
        stringResource(R.string.permanent_delete_body),
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
            Text(stringResource(R.string.deleted_days,
                DateFormat.getDateInstance().format(Date(item.deletedAt)), days),
                style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(onClick = onRestore, enabled = canModify) { Text(stringResource(R.string.restore)) }
                TextButton(onClick = onDelete, enabled = canModify) {
                    Text(stringResource(R.string.permanent_delete))
                }
            }
        }
    }
    HorizontalDivider(thickness = 1.dp, color = Color.White)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    vm: AppViewModel,
    isDefault: Boolean,
    onBack: () -> Unit,
    emailBackupVm: EmailBackupViewModel,
    onCreateBackup: () -> Unit,
    onBackupHistory: () -> Unit
) {
    val context = LocalContext.current
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val backupOptions by emailBackupVm.options.collectAsStateWithLifecycle()
    val backupSummary by emailBackupVm.summary.collectAsStateWithLifecycle()
    var colorCategory by rememberSaveable { mutableStateOf<SmsCategory?>(null) }
    val localeManager = remember { context.getSystemService(LocaleManager::class.java) }
    val languageTag = localeManager.applicationLocales.toLanguageTags()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            item {
                LanguageSelector(languageTag) { tag ->
                    localeManager.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                    else LocaleList.forLanguageTags(tag)
                }
                SettingLine(stringResource(R.string.default_sms_app),
                    stringResource(if (isDefault) R.string.default_set else R.string.default_not_set))
                val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                    PackageManager.PERMISSION_GRANTED
                SettingLine(stringResource(R.string.contacts_permission),
                    stringResource(if (contacts) R.string.granted else R.string.not_granted))
                TextButton(onClick = { openAppSettings(context) }) {
                    Text(stringResource(R.string.permission_settings))
                }
                val notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                SettingLine(stringResource(R.string.notifications_permission),
                    stringResource(if (notifications) R.string.granted else R.string.not_granted))
                TextButton(onClick = { openNotificationSettings(context) }) {
                    Text(stringResource(R.string.permission_settings))
                }
                SettingSwitch(
                    stringResource(R.string.notification_delete_left),
                    stringResource(if (prefs.notificationDeleteOnLeft)
                        R.string.notification_order_delete_left else R.string.notification_order_delete_right),
                    prefs.notificationDeleteOnLeft,
                    vm::setNotificationDeleteOnLeft
                )
                SettingSwitch(
                    stringResource(R.string.merge_sender),
                    stringResource(if (prefs.mergeConversations) R.string.merge_on else R.string.merge_off),
                    prefs.mergeConversations,
                    vm::setMergeConversations
                )
                SwipeSelector(stringResource(R.string.left_swipe), prefs.left, vm::setLeft)
                SwipeSelector(stringResource(R.string.right_swipe), prefs.right, vm::setRight)
                SettingLine(stringResource(R.string.trash_title), stringResource(R.string.trash_retention))
                SettingLine(stringResource(R.string.sms_classification),
                    stringResource(R.string.classification_detail))
                Text(stringResource(R.string.tag_colors), fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                SmsCategory.entries.forEach { category ->
                    TagColorSetting(category, prefs.tagColors.getValue(category)) {
                        colorCategory = category
                    }
                }
                Text(
                    stringResource(R.string.email_backup_title),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
                )
                SettingLine(
                    stringResource(R.string.default_recipient),
                    backupOptions.recipient.ifBlank { stringResource(R.string.not_set) }
                )
                SettingLine(
                    stringResource(R.string.last_confirmed_backup),
                    backupSummary.latestConfirmedAt?.let {
                        DateFormat.getDateTimeInstance().format(Date(it))
                    } ?: stringResource(R.string.none)
                )
                SettingLine(
                    stringResource(R.string.confirmed_backup_messages),
                    backupSummary.confirmedMessageCount.toString()
                )
                Row {
                    Button(onClick = onCreateBackup) {
                        Text(stringResource(R.string.create_email_backup))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onBackupHistory) {
                        Text(stringResource(R.string.backup_history))
                    }
                }
                SettingLine("MMS / RCS", stringResource(R.string.mms_unsupported_setting))
                SettingLine(stringResource(R.string.version), "JX SMS Reader 1.0")
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
    R.string.color_deep_blue to 0xFF245A9A,
    R.string.color_indigo to 0xFF3F51A3,
    R.string.color_purple to 0xFF60458F,
    R.string.color_rose to 0xFF9A365F,
    R.string.color_red to 0xFF9B3434,
    R.string.color_orange_brown to 0xFF9A4A20,
    R.string.color_gold_brown to 0xFF80620E,
    R.string.color_green to 0xFF276A43,
    R.string.color_teal to 0xFF147078,
    R.string.color_dark_gray to 0xFF515A66
)

@Composable
private fun TagColorSetting(category: SmsCategory, color: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryTag(category)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(categoryStringRes(category)), Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold)
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(color)))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.change), color = MaterialTheme.colorScheme.primary)
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
        title = { Text(stringResource(R.string.choose_tag_color,
            stringResource(categoryStringRes(category)))) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(TagColorPalette, key = { it.second }) { (name, color) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(color) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(color)))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(name), Modifier.weight(1f))
                        if (current == color) Text(stringResource(R.string.current),
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
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
        title = { Text(stringResource(R.string.change_category)) },
        text = {
            Column {
                SmsCategory.entries.forEach { category ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(category) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CategoryTag(category)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(categoryStringRes(category)), Modifier.weight(1f))
                        if (category == current) Text(stringResource(R.string.current),
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun SwipeSelector(title: String, value: SwipeAction, onChange: (SwipeAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp)) {
        Text(title, Modifier.weight(1f)); Text(stringResource(swipeActionStringRes(value)))
        DropdownMenu(open, onDismissRequest = { open = false }) {
            SwipeAction.entries.forEach { action ->
                DropdownMenuItem(text = { Text(stringResource(swipeActionStringRes(action))) },
                    onClick = { onChange(action); open = false })
            }
        }
    }
}

private data class LanguageOption(val tag: String, @param:StringRes val label: Int)

private val LanguageOptions = listOf(
    LanguageOption("", R.string.language_system),
    LanguageOption("zh-CN", R.string.language_zh_cn),
    LanguageOption("zh-TW", R.string.language_zh_tw),
    LanguageOption("ja", R.string.language_ja),
    LanguageOption("en", R.string.language_en),
    LanguageOption("ko", R.string.language_ko),
    LanguageOption("de", R.string.language_de)
)

@Composable
private fun LanguageSelector(currentTags: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = LanguageOptions.firstOrNull {
        it.tag.isNotEmpty() && currentTags.startsWith(it.tag, ignoreCase = true)
    } ?: LanguageOptions.first()
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.language), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text(stringResource(current.label))
        DropdownMenu(open, onDismissRequest = { open = false }) {
            LanguageOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    onClick = {
                        open = false
                        onChange(option.tag)
                    }
                )
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
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun UnsupportedSendScreen() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.unsupported_send), style = MaterialTheme.typography.headlineSmall)
    }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clipboard_label), text))
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

@StringRes
private fun categoryStringRes(category: SmsCategory): Int = when (category) {
    SmsCategory.OTP -> R.string.category_otp
    SmsCategory.ADVERTISEMENT -> R.string.category_ad
    SmsCategory.DELIVERY -> R.string.category_delivery
    SmsCategory.PERSON -> R.string.category_person
    SmsCategory.NOTICE -> R.string.category_notice
    SmsCategory.UNKNOWN -> R.string.category_other
}

@StringRes
private fun swipeActionStringRes(action: SwipeAction): Int = when (action) {
    SwipeAction.DELETE -> R.string.action_delete
    SwipeAction.MARK_READ_UNREAD -> R.string.action_mark_toggle
    SwipeAction.COPY_TEXT -> R.string.action_copy
    SwipeAction.NONE -> R.string.action_none
}

private object AvatarBitmapCache {
    private val cache = LruCache<String, android.graphics.Bitmap>(32)
    fun get(uri: Uri) = cache.get(uri.toString())
    fun put(uri: Uri, bitmap: android.graphics.Bitmap) = cache.put(uri.toString(), bitmap)
}
