package com.padvideo.data.repository

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

internal enum class SmbConnectionStep {
    CONNECT, AUTHENTICATE, LIST_SHARES, SHARE, BROWSE
}

internal class SmbConnectionFailure(
    step: SmbConnectionStep,
    guest: Boolean,
    port: Int,
    cause: Exception
) : IOException(describeConnectionFailure(step, guest, port, cause), cause)

private fun describeConnectionFailure(
    step: SmbConnectionStep,
    guest: Boolean,
    port: Int,
    cause: Exception
): String {
    val causes = generateSequence<Throwable>(cause) { it.cause }.take(12).toList()
    val status = causes.filterIsInstance<SMBApiException>().firstOrNull()?.status
    val loginHint = if (guest) {
        "共享需要账号密码，请填写 Windows / NAS 的用户名和密码"
    } else {
        "账号或密码错误，Windows 本地账号可尝试「电脑名\\用户名」"
    }
    val reason = when (status) {
        NtStatus.STATUS_LOGON_FAILURE -> loginHint
        NtStatus.STATUS_PASSWORD_EXPIRED -> "账号密码已过期"
        NtStatus.STATUS_ACCOUNT_DISABLED -> "账号已被禁用"
        NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED -> "Windows 策略未允许该账号通过网络登录"
        NtStatus.STATUS_BAD_NETWORK_NAME -> "找不到此共享名，请核对共享文件夹名称（或只填 IP 列出全部共享）"
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "共享内找不到此目录"
        NtStatus.STATUS_ACCESS_DENIED -> when {
            step == SmbConnectionStep.LIST_SHARES -> "服务器禁止匿名列出共享，请填写账号或直接填 smb://IP/共享名"
            guest || step == SmbConnectionStep.AUTHENTICATE -> loginHint
            else -> "该账号无权读取此共享，请检查共享权限与文件夹安全权限"
        }
        else -> when {
            causes.any { it is UnknownHostException } -> "无法解析主机名，请改用电脑或 NAS 的局域网 IP（如 192.168.1.x）"
            causes.any { it is ConnectException || it is NoRouteToHostException } ->
                "无法连接 TCP $port 端口，请确认在同一局域网且 Windows 防火墙已放行「文件和打印机共享」"
            causes.any { it is SocketTimeoutException || it is TimeoutException } ->
                if (step == SmbConnectionStep.CONNECT) {
                    "连接 TCP $port 超时，请检查 IP 地址、Wi-Fi 是否同网段以及防火墙设置"
                } else {
                    "服务器响应超时，请稍后重试"
                }
            step == SmbConnectionStep.CONNECT -> "SMB 协议协商失败，请确认设备已开启 SMB 2/3 服务"
            step == SmbConnectionStep.AUTHENTICATE -> loginHint
            step == SmbConnectionStep.LIST_SHARES -> "无法自动列出共享列表，请直接填写完整路径如 192.168.1.x/共享名"
            step == SmbConnectionStep.SHARE -> "无法打开共享目录，请核对共享名称和账号权限"
            else -> "读取目录失败，请检查权限设置"
        }
    }
    val detail = status?.takeUnless { it == NtStatus.STATUS_OTHER }?.let { " (${it.name})" }.orEmpty()
    return "SMB 连接失败：$reason$detail"
}
