package com.padvideo.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.padvideo.data.repository.SmbLocation
import com.padvideo.data.repository.SmbRepository
import java.io.EOFException
import java.io.IOException

@UnstableApi
internal class SmbDataSource(private val repository: SmbRepository) : BaseDataSource(true) {
    private var handle: SmbRepository.OpenFile? = null
    private var openedUri: Uri? = null
    private var offset = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val location = try {
            SmbLocation.parse(dataSpec.uri.toString())
        } catch (error: IllegalArgumentException) {
            throw IOException(error.message, error)
        }
        val opened = repository.open(location)
        if (dataSpec.position > opened.length) {
            opened.close()
            throw EOFException("SMB 读取位置超出文件末尾")
        }
        handle = opened
        openedUri = dataSpec.uri
        offset = dataSpec.position
        val available = opened.length - offset
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available
        else minOf(dataSpec.length, available)
        transferStarted(dataSpec)
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val current = handle ?: throw IOException("SMB 视频连接已关闭")
        val count = try {
            current.file.read(buffer, this.offset, offset, minOf(length.toLong(), remaining).toInt())
        } catch (error: RuntimeException) {
            throw IOException("SMB 视频读取失败", error)
        }
        if (count <= 0) throw EOFException("SMB 文件在读取期间缩短")
        this.offset += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        val current = handle ?: return
        handle = null
        openedUri = null
        try {
            current.close()
        } finally {
            transferEnded()
        }
    }
}
