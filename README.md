# JX SMS Reader

JX SMS Reader 是面向 Samsung Galaxy S26+（Android 16 / API 36）的个人离线短信阅读器。它可以逐条显示 SMS，也可以按发信者合并为会话，并且没有回复、新建、输入框或发送入口。应用不使用网络、云端 AI、统计 SDK 或广告 SDK。

> **重要风险：当前版本只可靠支持普通 SMS，不解析 MMS、群组彩信或 RCS。设为默认短信应用后，这些消息可能无法正常显示。** `WAP_PUSH_DELIVER` 到达时应用会保守地记录事件并显示“不支持 MMS”通知，不会伪装成已保存。

## 为什么必须成为默认短信应用

Android 只允许当前默认 SMS 应用写入和删除系统 Telephony SMS Provider。JX 的通知栏删除、应用垃圾箱、撤销及恢复都会更改 Provider，因此需要通过 `RoleManager.ROLE_SMS` 由用户主动授权。若 JX 不再是默认应用，收件箱仍可只读，但 UI 会进入受限模式并禁用滑动删除。

应用声明了系统默认 SMS 角色所需的 `SMS_DELIVER`、`WAP_PUSH_DELIVER`、`ACTION_SENDTO` 与 `RESPOND_VIA_MESSAGE` 组件。后两者只满足角色契约：外部发送 Intent 会明确显示“JX SMS Reader 不支持发送短信”，服务不会假装发送成功。

## 权限

- `READ_SMS`：读取系统 Inbox。
- `RECEIVE_SMS`：默认应用接收普通 SMS。
- `SEND_SMS`：默认 SMS 角色契约所需；产品 UI 不提供发送能力。
- `READ_CONTACTS`：可选，用于联系人名称和头像；拒绝后使用 Sender 和稳定字母头像。
- `POST_NOTIFICATIONS`：显示逐条短信通知及 MMS 风险通知。
- `RECEIVE_MMS`：接收并安全提示不支持的 MMS 事件。

没有网络、位置、存储、通讯录写入、通知读取、无障碍、悬浮窗、Root、Shizuku 或 `QUERY_ALL_PACKAGES` 权限。

## 核心流程与架构

- `data/sms` 使用 `ContentResolver` 查询/写入 `Telephony.Sms`，Cursor 均由 `use` 关闭；`ContentObserver` 驱动 StateFlow 刷新。
- `domain/classifier` 使用确定性的中/韩/英关键词规则，优先识别明确退订广告标记，其次认证语义、快递、通知、真人和未知。数字本身不会被判为 OTP。
- `receiver/IncomingSmsReceiver` 用 `goAsync` 和 IO Coroutine 合并 multipart PDU，以 Sender、正文、时间窗口和 subscriptionId 做短期幂等检查，然后写 Provider、分类并通知。
- `notification` 使用 `BigTextStyle`，标题以 `【分类】` 开头。点击打开只读详情；显式且不可变的 PendingIntent 分别执行“标为已读”和“删除”，用户可以设置删除按钮在左侧或右侧；没有 Reply action。
- `data/trash` 使用 Room，`originalSmsId` 有唯一索引。统一的 `moveSmsToTrash(id)` 先查询 Provider、写入并确认 Room 快照，再删除 Provider，最后标记完成。Provider 删除失败时备份保留，连续调用保持幂等。
- 恢复先插入 Provider，成功后才删 Room；不会发出“新短信”通知。WorkManager 每天清理超过 30 天的记录，App 启动也清理一次。
- `data/preferences` 使用 DataStore 保存左右滑动作（删除、已读/未读、复制、无操作）、通知按钮顺序、会话合并开关、每类标签颜色和单条短信的手动分类覆盖。
- Compose UI 包括首次风险说明、带搜索框和会话滑动批量删除确认的 Inbox、同时显示收件与已发送短信的发信者会话、可长按选取正文的只读详情、垃圾箱和设置。已发送短信使用浅蓝底金色 `JX` 头像；分类标签使用用户选择的深色，短信背景自动使用对应浅色。
- 通过 Android 原生每应用语言机制支持简体中文、繁体中文、日语、英语、韩语和德语，并支持跟随系统；主要界面、操作、分类和通知文案均提供对应资源。

## 图标

启动图标为本地 VectorDrawable：深蓝背景、青蓝信封线条、白色信封主体和粗体 `JX`。`mipmap-anydpi-v26` 提供 Adaptive Icon，重要字样位于安全区；`ic_launcher_monochrome.xml` 支持 themed icon。各密度目录仍提供 launcher fallback，不依赖在线素材。

## Galaxy S26+ 安装与首次设置

1. 在手机上打开“设置 → 关于手机 → 软件信息”，连续点击版本号启用开发者选项，再打开 USB 调试。
2. 连接电脑并确认设备：`adb devices`。
3. 构建后安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
4. 启动 JX，阅读 MMS/RCS 警告并主动勾选确认。
5. 点击“设为默认短信应用”，在系统角色对话框选择 JX。
6. 角色授予后允许短信权限；联系人权限可以拒绝；需要通知时允许通知权限。
7. 如需恢复原短信应用：手机“设置 → 应用 → 选择默认应用 → 短信应用”，重新选择 Samsung Messages 或原应用。

## 手动验收

### Inbox 与接收

1. 确认 Launcher 名称为 `JX SMS Reader`，图标可见 `JX` 和信封，在圆形/方圆形遮罩及浅深壁纸下不被裁切。
2. 设为默认应用后，确认现有短信按时间倒序显示；切换“合并同一发信者”后，可在逐条列表和会话列表之间切换。
3. 分别发送中文、韩文、英文、emoji 和 multipart SMS；确认只新增一次、完整正文可读、SIM subscriptionId 可用时显示。
4. 打开详情，确认没有输入/回复/发送控件，短信变为已读且对应通知取消。
5. 拒绝联系人权限后重新打开 App，确认 Sender 和稳定头像仍正常显示。

### 通知删除与普通划掉

1. 收到新 SMS，确认通知标题含 `【分类】`、正文可展开、无 Reply，同时存在“标为已读”和红色“删除”；切换设置后确认左右顺序随之改变。
2. 普通划掉一条通知，回到 JX 确认短信仍在 Inbox。
3. 再收到短信并点通知“删除”，确认通知消失、Inbox 移除、垃圾箱出现完整正文。
4. 在系统设置强制停止 JX 后重新打开，再收到短信并重复通知删除，验证进程重建路径。

### 垃圾箱、恢复与双 SIM

1. 左滑和右滑短信，确认达到阈值才执行，随后 5 秒 Snackbar 可撤销。
2. 在垃圾箱点“恢复”，确认短信回到 Inbox 且没有新短信声音；模拟失败时记录应保留。
3. 永久删除一条，确认二次提示；“清空”应明确提示不可撤销。
4. 将一条测试记录保留到过期边界或用测试代码验证，确认 30 天记录清理、未满 30 天保留。
5. 双 SIM 分别接收正文相同的短信，确认 subscriptionId 不同不会错误去重；详情显示相应 subscriptionId。
6. 发送 MMS，确认 App 不崩溃，显示“暂不支持 MMS”，且 Inbox 不出现空 SMS。

## 构建与测试

本机若未设置 `JAVA_HOME`，可指向 Android Studio 自带 JBR。PowerShell 示例：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat test
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

连接 API 36 设备后可运行：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Debug APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 已知限制

- 仅针对 API 36；未适配旧 Android、平板或其他厂商设备。
- 不解析、保存或呈现 MMS/RCS；切换默认应用期间这类消息存在无法查看的风险。
- App 没有发送或回复能力，外部 `SENDTO` 只显示不支持说明。
- 联系人照片读取依赖系统联系人权限；拒绝权限时使用生成头像。
- Room 与系统 Provider 无法共享事务。实现保证“先备份再删除”和可重试状态，但极端的系统进程终止仍需在垃圾箱中检查未完成备份。
- 已在连接的 Samsung Android 16 真机上确认默认短信角色、搜索、会话合并、分类配色、系统返回导航和通知按钮顺序；真实运营商 multipart、双 SIM 与锁屏接收仍建议按上述清单长期观察。
