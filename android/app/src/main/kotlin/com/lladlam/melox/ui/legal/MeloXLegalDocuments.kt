package com.lladlam.melox.ui.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.MeloXGlassDialog
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXSystemColors

const val MELOX_LEGAL_VERSION = "2.0-2026-09-25"

/** Synoc Music fork: maintainer links shown in the consent dialog, disclaimer and About page. */
object SynocProject {
    const val NAME = "Synoc Music"
    const val GITHUB_USER = "XIAOKU2300"
    const val GITHUB_URL = "https://github.com/XIAOKU2300"
    const val QQ_GROUP_NAME = "山灵音乐逆向反馈群"
    const val QQ_GROUP_NUMBER = "1124680973"
    const val QQ_GROUP_URL =
        "http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=9PFsj_0lDY35JcGVKDF-Znb9o7asAEfy&authKey=bqRpzOa1%2F%2Ba6RU%2FnMGWV1LcBFzEYglGtreTuj4SfUF257lneWWeaDUrPocrAQirE&noverify=0&group_code=1124680973"
    const val UPSTREAM_URL = "https://github.com/lladlam/MeloX-Android"
}

enum class MeloXLegalDocument(
    val title: String,
    internal val assetPath: String,
) {
    PrivacyPolicy("隐私政策", "legal/privacy-policy-zh-CN.md"),
    Disclaimer("免责声明与使用须知", "legal/disclaimer-zh-CN.md"),
    CloudControlPrivacy("云控隐私协议", "legal/cloud-control-privacy-zh-CN.md"),
    ThirdPartyMusicSources("第三方音乐源使用协议", "legal/third-party-music-sources-zh-CN.md"),
}

private enum class LegalBlockKind { Heading, Subheading, Paragraph, Bullet }

private data class LegalBlock(
    val kind: LegalBlockKind,
    val text: String,
)

@Composable
fun MeloXLegalLinks(
    modifier: Modifier = Modifier,
    tint: Color = MeloXSystemColors.Blue,
) {
    var selectedDocument by remember { mutableStateOf<MeloXLegalDocument?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegalLink("隐私政策", tint) { selectedDocument = MeloXLegalDocument.PrivacyPolicy }
            Text(
                text = "与",
                modifier = Modifier.padding(horizontal = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            LegalLink("免责声明", tint) { selectedDocument = MeloXLegalDocument.Disclaimer }
        }
        LegalLink("云控隐私协议", tint) { selectedDocument = MeloXLegalDocument.CloudControlPrivacy }
    }

    selectedDocument?.let { document ->
        MeloXLegalDocumentDialog(
            document = document,
            onDismiss = { selectedDocument = null },
        )
    }
}

@Composable
fun MeloXThirdPartyMusicSourceConsentDialog(
    onReject: () -> Unit,
    onAccept: () -> Unit,
) {
    var showPolicy by remember { mutableStateOf(false) }
    MeloXGlassDialog(visible = true, onDismiss = {}) {
        Text("开启第三方音乐源？", style = MaterialTheme.typography.titleLarge)
        Text(
            "第三方音乐源由用户自行配置和使用，不属于 MeloX 内置音乐服务，也不在 MeloX 云控范围内。启用前请阅读并同意第三方音乐源使用协议。",
            modifier = Modifier.padding(top = 9.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f),
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        Text(
            "查看第三方音乐源使用协议",
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { showPolicy = true }
                .padding(horizontal = 6.dp, vertical = 7.dp),
            color = MeloXSystemColors.Blue,
            fontWeight = FontWeight.Medium,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MeloXGlassButton(
                onClick = onReject,
                modifier = Modifier.weight(1f),
                style = MeloXGlassButtonStyle.Plain,
            ) { Text("不同意") }
            MeloXGlassButton(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                style = MeloXGlassButtonStyle.BorderedProminent,
            ) { Text("同意并开启") }
        }
    }
    if (showPolicy) {
        MeloXLegalDocumentDialog(
            document = MeloXLegalDocument.ThirdPartyMusicSources,
            onDismiss = { showPolicy = false },
        )
    }
}

@Composable
private fun LegalLink(text: String, tint: Color, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        color = tint,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun MeloXLegalDocumentDialog(
    document: MeloXLegalDocument,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val blocks = remember(document) {
        context.assets.open(document.assetPath).bufferedReader().use { reader ->
            parseLegalDocument(reader.readText())
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        MeloXSymbolIcon(
                            symbol = MeloXSymbol.Xmark,
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            contentDescription = "关闭${document.title}",
                            iconSize = 15.sp,
                        )
                    }
                    Text(
                        text = document.title,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(36.dp))
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 22.dp,
                        top = 8.dp,
                        end = 22.dp,
                        bottom = 32.dp,
                    ),
                ) {
                    itemsIndexed(blocks) { index, block ->
                        LegalBlockText(
                            block = block,
                            first = index == 0,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MeloXFirstLaunchLegalConsent(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onOpenProject: () -> Unit,
) {
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }
    MeloXGlassDialog(visible = true, onDismiss = {}) {
        Text("欢迎使用 ${SynocProject.NAME}", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "${SynocProject.NAME} 是基于开源项目 MeloX 修改的非官方社区版本，免费开源，按 GPLv3 发布。",
            modifier = Modifier.padding(top = 9.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .heightIn(max = 240.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConsentPoint("与山灵（Shanling）及各音乐平台均无隶属、合作或授权关系，商标归各自权利人所有。")
            ConsentPoint("SyncLink 功能仅用于控制你本人拥有的播放器，协议为互操作目的自行分析，不修改设备固件。")
            ConsentPoint("歌词、封面等内容来自第三方，版权归原作者；请仅在合法授权范围内使用。")
            ConsentPoint("软件按“现状”提供，不收费、不担保；使用风险由使用者自行承担。")
        }
        MeloXLegalLinks(modifier = Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegalLink("GitHub：${SynocProject.GITHUB_USER}", MeloXSystemColors.Blue) { openUrl(SynocProject.GITHUB_URL) }
            Text("·", modifier = Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            LegalLink("QQ 群 ${SynocProject.QQ_GROUP_NUMBER}", MeloXSystemColors.Blue) { openUrl(SynocProject.QQ_GROUP_URL) }
        }
        Text(
            text = "点击“同意并继续”即表示你已阅读并同意以上内容及隐私政策、免责声明，可随时在设置中重新查看。",
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f),
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Text(
            text = "上游项目 MeloX 与开源许可",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClick = onOpenProject)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            color = MeloXSystemColors.Blue,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MeloXGlassButton(
                onClick = onDecline,
                modifier = Modifier.weight(1f),
                style = MeloXGlassButtonStyle.Plain,
            ) { Text("不同意并退出") }
            MeloXGlassButton(
                onClick = onAgree,
                modifier = Modifier.weight(1f),
                style = MeloXGlassButtonStyle.BorderedProminent,
            ) { Text("同意并继续") }
        }
    }
}

@Composable
private fun ConsentPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "•",
            modifier = Modifier.padding(end = 6.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun LegalBlockText(block: LegalBlock, first: Boolean) {
    when (block.kind) {
        LegalBlockKind.Heading -> Text(
            text = block.text,
            modifier = Modifier.padding(top = if (first) 0.dp else 24.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 25.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Bold,
        )
        LegalBlockKind.Subheading -> Text(
            text = block.text,
            modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        LegalBlockKind.Paragraph -> Text(
            text = block.text,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        LegalBlockKind.Bullet -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "•",
                modifier = Modifier.padding(end = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            Text(
                text = block.text,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
    }
}

private fun parseLegalDocument(markdown: String): List<LegalBlock> = markdown
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .mapNotNull { line ->
        when {
            line.startsWith("# ") -> LegalBlock(LegalBlockKind.Heading, line.removePrefix("# "))
            line.startsWith("## ") -> LegalBlock(LegalBlockKind.Subheading, line.removePrefix("## "))
            line.startsWith("### ") -> LegalBlock(LegalBlockKind.Subheading, line.removePrefix("### "))
            line.startsWith("- ") -> LegalBlock(LegalBlockKind.Bullet, line.removePrefix("- "))
            line == "---" -> null
            else -> LegalBlock(LegalBlockKind.Paragraph, line)
        }
    }
    .toList()
