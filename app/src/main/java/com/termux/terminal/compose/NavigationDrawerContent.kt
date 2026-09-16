package com.termux.terminal.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import com.termux.R

/**
 * Navigation drawer content for the Termux main screen.
 *
 * @param isOpen Whether the drawer is open
 * @param onOpenChange Callback when the drawer open state changes
 * @param onFileManagerClick Callback when the file manager button is clicked
 * @param onSettingsClick Callback when the settings button is clicked
 * @param onToggleKeyboardClick Callback when the toggle keyboard button is clicked
 * @param modifier Modifier to apply
 * @param content The content to display inside the drawer
 */
@Composable
fun TermuxNavigationDrawer(
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onFileManagerClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onToggleKeyboardClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val drawerState = rememberDrawerState(
        initialValue = if (isOpen) DrawerValue.Open else DrawerValue.Closed
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(isOpen) {
        if (isOpen && drawerState.isClosed) {
            scope.launch { drawerState.open() }
        } else if (!isOpen && drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
    }

    LaunchedEffect(drawerState.currentValue) {
        onOpenChange(drawerState.currentValue == DrawerValue.Open)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp),
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.application_name),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.title_activity_file_manager)) },
                        selected = false,
                        onClick = onFileManagerClick,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.title_activity_file_manager)
                            )
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.action_open_settings)) },
                        selected = false,
                        onClick = onSettingsClick,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.action_open_settings)
                            )
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.toggle_keyboard)) },
                        selected = false,
                        onClick = onToggleKeyboardClick,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = stringResource(R.string.toggle_keyboard)
                            )
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        },
        content = content
    )
}
