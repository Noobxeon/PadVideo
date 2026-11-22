package com.padvideo.repository

import com.padvideo.data.model.MountedStorageSource
import com.padvideo.data.model.StorageMountType
import com.padvideo.data.repository.QuarkDriveRepository
import com.padvideo.data.repository.WebDavRepository
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavAndQuarkRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `WebDAV PROPFIND lists folders videos and attaches sidecar subtitles with auth header`() = runTest {
        var receivedAuth: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                receivedAuth = request.getHeader("Authorization")
                return MockResponse().setResponseCode(207).setBody(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <D:multistatus xmlns:D="DAV:">
                      <D:response>
                        <D:href>/dav/</D:href>
                        <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
                      </D:response>
                      <D:response>
                        <D:href>/dav/Anime/</D:href>
                        <D:propstat><D:prop><D:displayname>Anime</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
                      </D:response>
                      <D:response>
                        <D:href>/dav/Dune.2021.mkv</D:href>
                        <D:propstat><D:prop><D:displayname>Dune.2021.mkv</D:displayname><D:getcontentlength>409600</D:getcontentlength></D:prop></D:propstat>
                      </D:response>
                      <D:response>
                        <D:href>/dav/Dune.2021.zh.srt</D:href>
                        <D:propstat><D:prop><D:displayname>Dune.2021.zh.srt</D:displayname><D:getcontentlength>2048</D:getcontentlength></D:prop></D:propstat>
                      </D:response>
                    </D:multistatus>
                    """.trimIndent()
                )
            }
        }

        val davUrl = server.url("/dav/").toString()
        val mount = MountedStorageSource(
            type = StorageMountType.WEBDAV,
            name = "AList NAS",
            address = davUrl,
            username = "admin",
            password = "secret"
        )
        val repo = WebDavRepository()
        val entries = repo.listDirectory(mount, davUrl)

        assertTrue(receivedAuth?.startsWith("Basic ") == true)
        assertEquals(3, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("Anime", entries[0].name)

        val videoEntry = entries.first { it.isVideoFile }
        assertEquals("Dune.2021.mkv", videoEntry.name)
        assertEquals(1, videoEntry.sidecarSubtitles.size)
        assertTrue(videoEntry.videoItem!!.httpHeaders.containsKey("Authorization"))
    }

    @Test
    fun `QuarkDrive lists folders videos and resolves direct stream URL with Cookie and Referer`() = runTest {
        val baseUrl = server.url("/1/clouddrive").toString().trimEnd('/')
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/1/clouddrive/file/sort") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "status": 200,
                              "code": 0,
                              "data": {
                                "list": [
                                  {"fid": "folder_1", "file_name": "4K电影", "dir": true, "file_type": 0, "size": 0},
                                  {"fid": "vid_99", "file_name": "Blade.Runner.2049.mp4", "dir": false, "file_type": 1, "size": 888888},
                                  {"fid": "sub_99", "file_name": "Blade.Runner.2049.srt", "dir": false, "file_type": 1, "size": 4096}
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                    }
                    path.startsWith("/1/clouddrive/file/download") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "status": 200,
                              "code": 0,
                              "data": [
                                {"fid": "vid_99", "download_url": "https://cdn.quark.cn/stream/blade.mp4?auth=ok"}
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val mount = MountedStorageSource(
            type = StorageMountType.QUARK,
            name = "我的夸克网盘",
            address = "0",
            cookie = "__pus=demo_token_123456"
        )
        val repo = QuarkDriveRepository(apiBaseUrl = baseUrl)
        val entries = repo.listFolder(mount, "0")

        assertEquals(3, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("4K电影", entries[0].name)
        assertEquals("folder_1", entries[0].remoteId)

        val video = entries.first { it.isVideoFile }
        assertEquals("Blade.Runner.2049.mp4", video.name)
        assertEquals(1, video.sidecarSubtitles.size)

        val (streamUrl, headers) = repo.resolveStreamUrl(mount, video.remoteId!!)
        assertEquals("https://cdn.quark.cn/stream/blade.mp4?auth=ok", streamUrl)
        assertEquals("__pus=demo_token_123456", headers["Cookie"])
        assertEquals("https://pan.quark.cn/", headers["Referer"])
    }
}
