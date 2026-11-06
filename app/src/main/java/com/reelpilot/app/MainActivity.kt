package com.reelpilot.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.telegram.TgPollService
import com.reelpilot.app.ui.LogsScreen
import com.reelpilot.app.ui.QueueScreen
import com.reelpilot.app.ui.ReelPilotTheme
import com.reelpilot.app.ui.SettingsScreen
import com.reelpilot.app.ui.DashboardScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        lifecycleScope.launch {
            if (SettingsRepo.get(this@MainActivity).current().botToken.isNotBlank()) {
                TgPollService.start(this@MainActivity)
            }
        }
        setContent {
            ReelPilotTheme {
                AppScaffold()
            }
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 29) wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 100)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("dashboard", "Home", Icons.Rounded.DynamicFeed),
    Tab("queue", "Queue", Icons.Rounded.PlaylistPlay),
    Tab("settings", "Settings", Icons.Rounded.Settings),
    Tab("logs", "Logs", Icons.Rounded.Description)
)

@Composable
fun AppScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "dashboard"

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo("dashboard") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") { DashboardScreen(onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } }) }
            composable("queue") { QueueScreen() }
            composable("settings") { SettingsScreen() }
            composable("logs") { LogsScreen() }
        }
    }
}
