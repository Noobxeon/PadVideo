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
    CONNECT, AUTHENTICATE, SHARE, BROWSE
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
    val loginHint = if (guest) "共享可能需要账号，请填写 Windows 或 NAS 的账号和密码"
    else "请核对账号和密码，Windows 本地账号可填写「电脑名\\用户名」"
    val reason = when (status) {
        NtStatus.STATUS_LOGON_FAILURE -> loginHint
        NtStatus.STATUS_PASSWORD_EXPIRED -> "账号密码已过期"
        NtStatus.STATUS_ACCOUNT_DISABLED -> "账号已被禁用"
        NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED -> "Windows 策略未允许该账号通过网络登录"
        NtStatus.STATUS_BAD_NETWORK_NAME -> "Windows 或 NAS 上找不到此共享名，请核对文件夹的共享名称"
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "共享内找不到此目录"
        NtStatus.STATUS_ACCESS_DENIED -> if (guest || step == SmbConnectionStep.AUTHENTICATE) loginHint
            else "账号无权读取此共享，请检查共享权限和文件夹的安全权限"
        else -> when {
            causes.any { it is UnknownHostException } -> "找不到电脑名，请试用 Windows 电脑的局域网 IP 地址"
            causes.any { it is ConnectException || it is NoRouteToHostException } ->
                "无法连接服务器的 TCP $port 端口，请检查局域网和 Windows 的文件共享防火墙规则"
            causes.any { it is SocketTimeoutException || it is TimeoutException } -> if (step == SmbConnectionStep.CONNECT)
                "连接 TCP $port 超时，请检查 Windows 电脑地址、局域网和文件共享防火墙规则"
                else "服务器响应超时，请检查网络后重试"
            step == SmbConnectionStep.CONNECT -> "SMB 协商失败，请检查电脑是否启用了 SMB 2/3 文件共享"
            step == SmbConnectionStep.AUTHENTICATE -> loginHint
            step == SmbConnectionStep.SHARE -> "无法打开共享，请检查共享名和账号权限"
            else -> "无法读取共享目录，请检查路径和读取权限"
        }
    }
    val detail = status?.takeUnless { it == NtStatus.STATUS_OTHER }?.let { "（${it.name}）" }.orEmpty()
    return "SMB 连接失败，$reason$detail"
}
