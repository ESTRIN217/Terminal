package com.termux.terminal.compose.settings

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.termux.R
import com.termux.shared.android.AndroidUtils
import com.termux.shared.android.PackageUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.terminal.compose.SettingsBadge
import com.termux.terminal.compose.SettingsCardGroup
import com.termux.terminal.compose.SettingsListTile
import com.termux.terminal.compose.SettingsSectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_TAG = "AboutScreen"

private const val GITHUB_DEV_URL = "https://github.com/ESTRIN217"
private const val GITHUB_REPO_URL = "https://github.com/ESTRIN217/Terminal"
private const val GITHUB_ISSUES_URL = "https://github.com/ESTRIN217/Terminal/issues"
private const val GITHUB_LICENSE_URL = "https://github.com/ESTRIN217/Terminal/blob/master/LICENSE"
private const val GITHUB_AVATAR_URL = "https://github.com/ESTRIN217.png"

private val DEBIAN_OS_RELEASE_PATH = TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/etc/os-release"
private val DEBIAN_VERSION_FILE_PATH = TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/etc/debian_version"
private val DEBIAN_LOGO_PATH = TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/usr/share/pixmaps/debian-logo.png"

private const val ABOUT_SAVE_FILE_NAME = "Terminal-about.log"

private enum class AboutAction {
    SHARE,
    COPY,
    SAVE
}

private data class DebianInfo(
    val isInstalled: Boolean,
    val prettyName: String?,
    val version: String?,
    val hasLogo: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onNavigateToLicenses: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    val versionName = remember { PackageUtils.getVersionNameForPackage(context) }
    val platformLabel = stringResource(R.string.about_platform_android)
    val archLabel = remember {
        if (Build.SUPPORTED_ABIS.isNotEmpty()) {
            Build.SUPPORTED_ABIS[0].uppercase()
        } else {
            "—"
        }
    }
    val debianInfo by produceState<DebianInfo>(
        initialValue = DebianInfo(isInstalled = false, prettyName = null, version = null, hasLogo = false)
    ) {
        value = withContext(Dispatchers.IO) { loadDebianInfo() }
    }

    val coroutineScope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

    fun runReportAction(action: AboutAction) {
        coroutineScope.launch {
            val report = try {
                withContext(Dispatchers.IO) { buildAboutReport(context) }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to build about report", e)
                Toast.makeText(context, context.getString(R.string.about_generate_info_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                when (action) {
                    AboutAction.SHARE -> ShareUtils.shareText(
                        context,
                        context.getString(R.string.about_share_subject, context.getString(R.string.application_name)),
                        report,
                        context.getString(R.string.about_share_chooser_title)
                    )
                    AboutAction.COPY -> ShareUtils.copyTextToClipboard(
                        context,
                        context.getString(R.string.about_copy_label),
                        report,
                        context.getString(R.string.about_info_copied)
                    )
                    AboutAction.SAVE -> ShareUtils.saveTextToFile(
                        context,
                        context.getString(R.string.about_report_label),
                        Environment.getExternalStorageDirectory().toString() + "/" + ABOUT_SAVE_FILE_NAME,
                        report,
                        true,
                        -1
                    )
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "About report action failed", e)
                Toast.makeText(context, context.getString(R.string.about_action_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val openUrl = { url: String ->
        ShareUtils.openUrl(context, url)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about_preference_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_go_back)
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Compartir, copiar o guardar la información"
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    runReportAction(AboutAction.SHARE)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_copy)) },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    runReportAction(AboutAction.COPY)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_to_a_files)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Save, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    runReportAction(AboutAction.SAVE)
                                }
                            )
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                HeaderCard(
                    image = R.mipmap.ic_launcher,
                    appName = stringResource(R.string.application_name),
                    platformLabel = platformLabel,
                    version = versionName,
                    archLabel = archLabel
                )
                if (debianInfo.isInstalled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    HeaderCard(
                        image = null,
                        imageFallbackIcon = Icons.Default.Terminal,
                        appName = stringResource(R.string.about_proot),
                        platformLabel = platformLabel,
                        version = null,
                        archLabel = archLabel
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    HeaderCard(
                        image = if (debianInfo.hasLogo) File(DEBIAN_LOGO_PATH) else null,
                        imageFallbackIcon = Icons.Default.Code,
                        appName = debianInfo.prettyName ?: stringResource(R.string.about_debian),
                        platformLabel = platformLabel,
                        version = debianInfo.version,
                        archLabel = archLabel
                    )
                }
            }
            item {
                SettingsCardGroup {
                    SettingsListTile(
                        leadingIcon = Icons.Default.Description,
                        title = stringResource(R.string.about_proot_uses_debian),
                        subtitle = stringResource(R.string.about_fork_subtitle),
                        onClick = {}
                    )
                }
            }

            item {
                SettingsSectionTitle(title = stringResource(R.string.about_section_developer))
                DeveloperCard(
                    onGithubClick = { openUrl(GITHUB_DEV_URL) }
                )
            }

            item {
                SettingsSectionTitle(title = stringResource(R.string.about_section_useful_links))
                SettingsCardGroup {
                    SettingsListTile(
                        leadingIcon = Icons.Default.Description,
                        title = stringResource(R.string.open_source_licenses),
                        subtitle = stringResource(R.string.about_third_party_library),
                        trailingIcon = Icons.Default.ChevronRight,
                        onClick = onNavigateToLicenses
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SettingsListTile(
                        leadingIcon = Icons.Default.Code,
                        title = stringResource(R.string.about_view_repository),
                        subtitle = stringResource(R.string.about_repository_name),
                        trailingIcon = Icons.Default.ChevronRight,
                        onClick = { openUrl(GITHUB_REPO_URL) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SettingsListTile(
                        leadingIcon = Icons.Default.BugReport,
                        title = stringResource(R.string.about_report_issue),
                        subtitle = stringResource(R.string.about_issues_path),
                        trailingIcon = Icons.Default.ChevronRight,
                        onClick = { openUrl(GITHUB_ISSUES_URL) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SettingsListTile(
                        leadingIcon = Icons.Default.Description,
                        title = stringResource(R.string.about_license_gplv3),
                        subtitle = stringResource(R.string.about_open_source_software),
                        trailingIcon = Icons.Default.ChevronRight,
                        onClick = { openUrl(GITHUB_LICENSE_URL) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.about_made_with),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(
    image: Any?,
    appName: String,
    platformLabel: String,
    version: String?,
    archLabel: String,
    imageFallbackIcon: ImageVector = Icons.Default.Code
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = appName,
                    modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = imageFallbackIcon,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsBadge(text = platformLabel)
                    if (version != null) {
                        SettingsBadge(text = stringResource(R.string.about_version_prefix) + version)
                    }
                    SettingsBadge(text = archLabel)
                }
            }
        }
    }
}

@Composable
private fun DeveloperCard(
    onGithubClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = GITHUB_AVATAR_URL,
                    contentDescription = "Desarrollador",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_developer_name),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = stringResource(R.string.about_main_developer),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                SocialButton(
                    icon = Icons.Default.Code,
                    label = stringResource(R.string.about_view_on_github),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onGithubClick
                )
            }
        }
    }
}

@Composable
private fun SocialButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

private fun buildAboutReport(context: Context): String {
    val aboutString = StringBuilder()
    aboutString.append(
        TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES)
    )
    aboutString.append("\n\n")
        .append(AndroidUtils.getDeviceInfoMarkdownString(context, true))
    aboutString.append("\n\n")
        .append(TermuxUtils.getImportantLinksMarkdownString(context))
    return aboutString.toString()
}

private fun loadDebianInfo(): DebianInfo {
    if (!isDebianInstalled()) {
        return DebianInfo(isInstalled = false, prettyName = null, version = null, hasLogo = false)
    }
    val prettyName = readOsReleasePrettyName()
        ?: readFileBestEffort(DEBIAN_VERSION_FILE_PATH)
    val version = readFileBestEffort(DEBIAN_VERSION_FILE_PATH)
    val hasLogo = File(DEBIAN_LOGO_PATH).isFile
    return DebianInfo(
        isInstalled = true,
        prettyName = prettyName,
        version = version,
        hasLogo = hasLogo
    )
}

private fun isDebianInstalled(): Boolean {
    return File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/bin/bash").isFile &&
        File(DEBIAN_VERSION_FILE_PATH).isFile
}

private fun readOsReleasePrettyName(): String? {
    val content = readFileBestEffort(DEBIAN_OS_RELEASE_PATH) ?: return null
    val line = content.lineSequence()
        .firstOrNull { it.trim().startsWith("PRETTY_NAME=") }
        ?: return null
    return line.substringAfter('=').trim().trim('"').ifEmpty { null }
}

private fun readFileBestEffort(path: String): String? {
    return try {
        val content = File(path).readText().trim()
        content.ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}

@Preview
@Composable
fun AboutScreenPreview() {
    AboutScreen(
        onBack = {},
    )
}