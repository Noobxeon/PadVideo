package com.padvideo.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.padvideo.AppContainer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class PadVideoTabletUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `tablet unified file browser mounts sheet and commercial player controls work end to end`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)
        val mainViewModel = MainViewModel(container)
        val playerViewModel = PlayerViewModel(context, container)

        // Prepare nested directories to verify multi-level subfolder drill-down
        val moviesDir = tempFolder.newFolder("TabletMovies")
        val subDir = File(moviesDir, "SciFi").apply { mkdirs() }
        File(subDir, "Interstellar.2014.1080p.mp4").writeBytes(ByteArray(4096))
        File(subDir, "Interstellar.2014.1080p.zh.srt").writeText(
            "1\n00:00:01,000 --> 00:00:04,000\n不要温和地走进那个良夜\n"
        )

        composeRule.setContent {
            PadVideoApp(
                container = container,
                mainViewModel = mainViewModel,
                playerViewModel = playerViewModel
            )
        }

        // 1. Verify Tablet NavigationRail & Library Header
        composeRule.onNodeWithText("PadVideo").assertIsDisplayed()
        composeRule.onNodeWithText("平板影院媒体库").assertIsDisplayed()

        // 2. Switch to unified File Browser tab, drill into subfolder, and verify Add Mount sheet (SMB/Quark/WebDAV/SAF)
        composeRule.onNodeWithText("文件浏览").performClick()
        composeRule.onNodeWithText("平板文件浏览器").assertIsDisplayed()

        mainViewModel.openDirectory(moviesDir)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SciFi").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Interstellar.2014.1080p.mp4").assertIsDisplayed()
        composeRule.onNodeWithText("外挂字幕×1").assertIsDisplayed()

        // Open Directory Batch Subtitle Sheet (Silero VAD + SMB/Local write-back)
        composeRule.onNodeWithText("批量生成字幕").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("目录批量生成字幕（自动写回目录）").assertIsDisplayed()
        composeRule.onNodeWithText("Silero VAD 神经网络精准断句（推荐）").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("关闭批量字幕面板").performClick()
        composeRule.waitForIdle()

        // Open unified Add Mount sheet in the same tab
        composeRule.onNodeWithText("添加挂载").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Windows / NAS").assertIsDisplayed()
        composeRule.onNodeWithText("夸克网盘").assertIsDisplayed()
        composeRule.onNodeWithText("WebDAV").assertIsDisplayed()
        composeRule.onNodeWithText("本地目录 / U盘").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.waitForIdle()

        // 3. Switch to History & Stream tab and launch the Sintel Demo Stream with Bilingual Translation
        composeRule.onNodeWithText("历史与串流").performClick()
        composeRule.onNodeWithText("网络串流直连播放").assertIsDisplayed()

        composeRule.onAllNodesWithText("试播")[0].performClick()
        composeRule.waitForIdle()

        // 4. Verify Commercial Video Player Overlay & Controls
        composeRule.onNodeWithText("在线字幕").assertIsDisplayed()
        composeRule.onNodeWithText("实时翻译 · 双语").assertIsDisplayed()

        // Open Real-Time Translation & Built-In Video Audio AI ASR Sheet
        composeRule.onNodeWithText("实时翻译 · 双语").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("无字幕视频原声识别引擎（直采音轨 · 戴耳机可用）").assertIsDisplayed()
        composeRule.onNodeWithText("一键下载离线识别模型并开启（国内镜像）").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.waitForIdle()

        // Open Interactive Bilingual Transcript Side-Drawer
        composeRule.onNodeWithContentDescription("双语台词本分栏").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("平板双语台词本 (5)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("关闭台词本").performClick()
        composeRule.waitForIdle()

        // Test Screen Lock & Unlock
        composeRule.onNodeWithContentDescription("锁定屏幕防误触").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("点击解锁屏幕").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("点击解锁屏幕").performClick()
        composeRule.waitForIdle()

        // Exit player & verify playback history recorded
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        assertTrue(container.settingsRepository.history.value.isNotEmpty())
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xhdpi")
    fun `portrait layout renders clean bottom nav unified mounts and 2-row player controls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)
        val mainViewModel = MainViewModel(container)
        val playerViewModel = PlayerViewModel(context, container)

        composeRule.setContent {
            PadVideoApp(
                container = container,
                mainViewModel = mainViewModel,
                playerViewModel = playerViewModel
            )
        }

        composeRule.onNodeWithText("平板影院媒体库").assertIsDisplayed()
        composeRule.onNodeWithText("文件浏览").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("平板文件浏览器").assertIsDisplayed()
        composeRule.onNodeWithText("添加挂载").assertIsDisplayed()

        composeRule.onNodeWithText("历史与串流").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("试播")[0].performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("实时翻译 · 双语").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("更多工具").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
    }
}
