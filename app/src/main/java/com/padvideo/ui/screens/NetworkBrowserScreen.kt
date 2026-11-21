package com.padvideo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.padvideo.data.model.formatFileSize
import com.padvideo.data.repository.SmbRepository
import com.padvideo.ui.NetworkBrowserState
import java.net.URI

@Composable
internal fun NetworkBrowserScreen(
    state: NetworkBrowserState,
    onConnect: (String, String, String, String) -> Unit,
    onOpenEntry: (SmbRepository.Entry) -> Unit,
    onRefresh: () -> Unit,
    onUp: () -> Unit,
    onDirectPlay: (String) -> Unit
) {
    var showConnect by remember { mutableStateOf(false) }
    var showDirectPlay by remember { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var streamUrl by rememberSaveable { mutableStateOf("") }
    var streamError by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showConnect = true }, enabled = !state.loading) { Text("连接 SMB") }
            TextButton(onClick = { showDirectPlay = true }) { Text("播放网络地址") }
        }
        state.location?.let { folder ->
            Text(folder.uri, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUp, enabled = !folder.isServer && !state.loading) {
                    Text("上一级")
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                label = { Text("过滤当前目录") }, modifier = Modifier.fillMaxWidth()
            )
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.location == null && !state.loading && state.message == null) {
            Text("输入 Windows 电脑或 NAS 的 IP、电脑名或共享路径。")
        }
        val visibleEntries = state.entries.filter { it.name.contains(search.trim(), ignoreCase = true) }
        if (state.location != null && !state.loading && visibleEntries.isEmpty() && state.message == null) {
            Text(
                if (state.location.isServer && state.entries.isEmpty()) "没有可浏览的共享。也可以输入完整共享路径。"
                else if (state.location.isServer) "没有匹配的共享"
                else "当前目录没有匹配的视频或子文件夹"
            )
        }
        LazyColumn {
            items(visibleEntries, key = { it.location.uri }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name) },
                    supportingContent = { Text(if (state.location?.isServer == true) "共享文件夹" else if (entry.isFolder) "文件夹" else formatFileSize(entry.size)) },
                    modifier = Modifier.clickable(enabled = !state.loading) {
                        if (entry.isFolder) search = ""
                        onOpenEntry(entry)
                    }
                )
            }
        }
    }
    if (showConnect) {
        var user by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var domain by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showConnect = false },
            title = { Text("连接 SMB 共享") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        address, { address = it }, singleLine = true,
                        label = { Text("电脑或共享地址") },
                        placeholder = { Text("192.168.1.20") }
                    )
                    OutlinedTextField(
                        user, { user = it }, singleLine = true,
                        label = { Text("账号（留空为访客）") },
                        placeholder = { Text("电脑名\\用户名") }
                    )
                    OutlinedTextField(
                        password, { password = it }, singleLine = true,
                        label = { Text("账号密码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("Windows 共享使用账号密码。") }
                    )
                    OutlinedTextField(domain, { domain = it }, singleLine = true, label = { Text("域（可选）") })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = address.isNotBlank(),
                    onClick = {
                        search = ""
                        onConnect(address, user, password, domain)
                        showConnect = false
                    }
                ) { Text("连接") }
            },
            dismissButton = { TextButton(onClick = { showConnect = false }) { Text("取消") } }
        )
    }
    if (showDirectPlay) {
        AlertDialog(
            onDismissRequest = { showDirectPlay = false },
            title = { Text("播放网络地址") },
            text = {
                OutlinedTextField(
                    streamUrl, { streamUrl = it; streamError = false }, singleLine = true,
                    label = { Text("HTTP、HTTPS 或 RTSP 地址") },
                    isError = streamError,
                    supportingText = { if (streamError) Text("请输入完整的视频地址") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = streamUrl.isNotBlank(),
                    onClick = {
                        val uri = runCatching { URI(streamUrl.trim()) }.getOrNull()
                        if (uri?.scheme?.lowercase() in listOf("http", "https", "rtsp") && uri?.host != null) {
                            onDirectPlay(streamUrl.trim())
                            showDirectPlay = false
                        } else {
                            streamError = true
                        }
                    }
                ) { Text("播放") }
            },
            dismissButton = { TextButton(onClick = { showDirectPlay = false }) { Text("取消") } }
        )
    }
}
