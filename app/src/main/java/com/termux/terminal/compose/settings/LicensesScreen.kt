package com.termux.terminal.compose.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.termux.shared.interact.ShareUtils
import com.termux.terminal.compose.SettingsCardGroup
import com.termux.terminal.compose.SettingsListTile
import com.termux.terminal.compose.SettingsSectionTitle

private data class LicenseInfo(
    val name: String,
    val detail: String,
    val url: String
)

private val LICENSES = listOf(
    LicenseInfo(
        name = "GPLv3",
        detail = "Licencia del proyecto Terminal",
        url = "https://www.gnu.org/licenses/gpl-3.0.html"
    ),
    LicenseInfo(
        name = "Apache 2.0",
        detail = "Librerías terminal-emulator y terminal-view",
        url = "https://www.apache.org/licenses/LICENSE-2.0"
    ),
    LicenseInfo(
        name = "MIT",
        detail = "TermuxConstants y TermuxPropertyConstants",
        url = "https://opensource.org/license/mit"
    ),
    LicenseInfo(
        name = "GPLv2 con excepción Classpath",
        detail = "OpenJDK y librerías de clase",
        url = "https://openjdk.org/legal/gplv2+ce.html"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Licencias",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
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
                SettingsSectionTitle(title = "Licencias de código abierto")
                SettingsCardGroup {
                    LICENSES.forEachIndexed { index, license ->
                        SettingsListTile(
                            leadingIcon = Icons.Default.Description,
                            title = license.name,
                            subtitle = license.detail,
                            trailingIcon = Icons.Default.ChevronRight,
                            onClick = { ShareUtils.openUrl(context, license.url) }
                        )
                        if (index < LICENSES.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = "Terminal se distribuye bajo GPLv3 e incluye componentes de terceros con sus respectivas licencias.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview
@Composable
fun LicensesScreenPreview() {
    LicensesScreen(
        onBack = {},
    )
}