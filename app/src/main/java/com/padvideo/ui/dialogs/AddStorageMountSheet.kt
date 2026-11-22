package com.padvideo.ui.dialogs

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.padvideo.data.model.MountedStorageSource
import com.padvideo.data.model.StorageMountType
import java.util.UUID

private enum class MountSheetTab(val label: String) {
    SMB("Windows / NAS"),
    QUARK("夸克网盘"),
    WEBDAV("WebDAV"),
    LOCAL_SAF("本地目录 / U盘")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddStorageMountSheet(
    editingSource: MountedStorageSource? = null,
    discoveredLanHosts: List<String>,
    isScanningLan: Boolean,
    onScanLanHosts: () -> Unit,
    onSaveMount: (MountedStorageSource) -> String?,
    onDeleteMount: ((String) -> Unit)? = null,
    onPickSafDirectory: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedTab by remember(editingSource) {
        mutableStateOf(
            when (editingSource?.type) {
                StorageMountType.SMB -> MountSheetTab.SMB
                StorageMountType.QUARK -> MountSheetTab.QUARK
                StorageMountType.WEBDAV -> MountSheetTab.WEBDAV
                null -> MountSheetTab.SMB
            }
        )
    }

    var name by remember(editingSource) { mutableStateOf(editingSource?.name.orEmpty()) }
    var address by remember(editingSource) { mutableStateOf(editingSource?.address.orEmpty()) }
    var username by remember(editingSource) { mutableStateOf(editingSource?.username.orEmpty()) }
    var password by remember(editingSource) { mutableStateOf(editingSource?.password.orEmpty()) }
    var domain by remember(editingSource) { mutableStateOf(editingSource?.domain.orEmpty()) }
    var cookie by remember(editingSource) { mutableStateOf(editingSource?.cookie.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var showQuarkWebLogin by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (editingSource != null) "编辑存储源" else "添加网络 / 网盘 / 本地挂载",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "所有本地、U盘、Windows共享、NAS 与夸克网盘统一在「文件浏览」管理",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }

            // Storage Type Selector Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = if (editingSource != null) {
                    listOf(selectedTab)
                } else {
                    MountSheetTab.entries
                }
                tabs.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            validationError = null
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = when (tab) {
                                    MountSheetTab.SMB -> Icons.Outlined.Computer
                                    MountSheetTab.QUARK -> Icons.Outlined.Cloud
                                    MountSheetTab.WEBDAV -> Icons.Outlined.Language
                                    MountSheetTab.LOCAL_SAF -> Icons.Outlined.Usb
                                },
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }

            when (selectedTab) {
                MountSheetTab.SMB -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "局域网 Windows / NAS 发现",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = onScanLanHosts,
                                    enabled = !isScanningLan,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    if (isScanningLan) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isScanningLan) "扫描中…" else "一键扫描")
                                }
                            }
                            if (discoveredLanHosts.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    discoveredLanHosts.forEach { ip ->
                                        AssistChip(
                                            onClick = {
                                                address = ip
                                                if (name.isBlank()) name = "局域网 $ip"
                                                validationError = null
                                            },
                                            label = { Text(ip) }
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "可直接填 IP（如 192.168.1.10）自动列出全部共享，或填 \\\\192.168.1.10\\Movies",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it; validationError = null },
                        label = { Text("服务器 IP 或共享路径 *") },
                        placeholder = { Text("192.168.1.10 或 \\\\PC\\Movies 或 smb://nas/Movies") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("显示名称（可选，如 客厅NAS / Windows电脑）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; validationError = null },
                        label = { Text("账号（留空为访客/匿名，支持 电脑名\\用户名）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; validationError = null },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it; validationError = null },
                        label = { Text("工作组 / 域（可选，通常留空）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                MountSheetTab.QUARK -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "夸克网盘直连挂载",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "支持网页扫码/手机号登录后一键提取 Cookie 直连浏览与原画播放；如果你有 AList/OpenList，也可以切到「WebDAV」挂载。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = { showQuarkWebLogin = true },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("网页登录夸克网盘（自动获取授权）")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("网盘名称（默认：夸克网盘）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it; validationError = null },
                        label = { Text("夸克 Cookie *（网页登录自动填充，也可手动粘贴）") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                MountSheetTab.WEBDAV -> {
                    Text(
                        text = "支持 AList / OpenList（夸克、阿里云盘、百度网盘、115）、群晖、飞牛 fnOS、威联通、绿联 NAS、坚果云与 Nextcloud。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it; validationError = null },
                        label = { Text("WebDAV 地址 *") },
                        placeholder = { Text("http://192.168.1.10:5244/dav") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("显示名称（可选，如 AList 夸克 / 飞牛 WebDAV）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名（免认证可留空）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码 / 应用专用密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                MountSheetTab.LOCAL_SAF -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "挂载本地目录或 USB-C 外接硬盘 / U盘",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "调用 Android 系统文件选择器（SAF）授权任意文件夹或 OTG U盘，授权后支持任意多层子目录进入与外挂字幕自动关联。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    onDismiss()
                                    onPickSafDirectory()
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("选择并挂载目录 / U盘")
                            }
                        }
                    }
                }
            }

            validationError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (selectedTab != MountSheetTab.LOCAL_SAF) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (editingSource != null && onDeleteMount != null) {
                        OutlinedButton(
                            onClick = {
                                onDeleteMount(editingSource.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除")
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }

                    Button(
                        onClick = {
                            val type = when (selectedTab) {
                                MountSheetTab.SMB -> StorageMountType.SMB
                                MountSheetTab.WEBDAV -> StorageMountType.WEBDAV
                                MountSheetTab.QUARK -> StorageMountType.QUARK
                                MountSheetTab.LOCAL_SAF -> StorageMountType.SMB
                            }
                            val candidate = MountedStorageSource(
                                id = editingSource?.id ?: UUID.randomUUID().toString(),
                                type = type,
                                name = name,
                                address = if (type == StorageMountType.QUARK) address.ifBlank { "0" } else address,
                                username = username,
                                password = password,
                                domain = domain,
                                cookie = cookie
                            )
                            val err = onSaveMount(candidate)
                            if (err == null) {
                                onDismiss()
                            } else {
                                validationError = err
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("保存并连接")
                    }
                }
            }
        }
    }

    if (showQuarkWebLogin) {
        QuarkWebLoginDialog(
            onCookieCaptured = { captured ->
                cookie = captured
                if (name.isBlank()) name = "夸克网盘"
                validationError = null
                showQuarkWebLogin = false
            },
            onDismiss = { showQuarkWebLogin = false }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun QuarkWebLoginDialog(
    onCookieCaptured: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var statusTip by remember { mutableStateOf("请在下方登录夸克网盘账号，登录进入网盘列表后点击右上角「完成授权」") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "登录夸克网盘",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = statusTip,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Button(
                            onClick = {
                                val cm = CookieManager.getInstance()
                                cm.flush()
                                val c1 = cm.getCookie("https://drive-pc.quark.cn").orEmpty()
                                val c2 = cm.getCookie("https://pan.quark.cn").orEmpty()
                                val merged = mergeCookies(c2, c1)
                                if (merged.contains("__pus") || merged.contains("__puus") || merged.length > 40) {
                                    onCookieCaptured(merged)
                                } else {
                                    statusTip = "尚未检测到登录态，请先完成登录后再点「完成授权」"
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("完成授权")
                        }
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = WebChromeClient()
                            webViewClient = WebViewClient()
                            loadUrl("https://pan.quark.cn/")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

private fun mergeCookies(first: String, second: String): String {
    val map = linkedMapOf<String, String>()
    listOf(first, second).forEach { raw ->
        raw.split(';').forEach { pair ->
            val trimmed = pair.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
            }
        }
    }
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
