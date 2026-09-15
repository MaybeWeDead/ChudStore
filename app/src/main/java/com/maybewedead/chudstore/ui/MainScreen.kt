package com.maybewedead.chudstore.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.maybewedead.chudstore.data.InstalledApp

// -----------------------------------------------------------------------
// MainScreen.kt — iOS App Store-подобный список: белые/тёмные карточки на
// приглушённом grouped-фоне (см. Theme.kt), крупные скруглённые иконки
// приложений, минимум визуального шума. Свайпов a-la iOS Mail не делаем
// (SwipeToDismiss в Compose Material3 всё ещё experimental и капризный
// на старых устройствах) — вместо этого простая иконка корзины на
// каждой карточке с confirm-диалогом, надёжнее и предсказуемее.
// -----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onInstallClick: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingUninstall by remember { mutableStateOf<InstalledApp?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshShizukuStatus()
        viewModel.refreshInstalledApps(context)
    }

    LaunchedEffect(viewModel.lastActionMessage) {
        viewModel.lastActionMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearActionMessage()
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Apps", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onInstallClick) {
                Icon(Icons.Filled.Add, contentDescription = "Install APK")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            if (!viewModel.shizukuAvailable || !viewModel.shizukuPermissionGranted) {
                ShizukuStatusBanner(
                    available = viewModel.shizukuAvailable,
                    granted = viewModel.shizukuPermissionGranted,
                    onRequestPermission = { viewModel.requestShizukuPermission() },
                )
            }

            if (viewModel.isBusy) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(viewModel.installedApps, key = { it.packageName }) { app ->
                    AppCard(
                        app = app,
                        onDeleteClick = { pendingUninstall = app },
                    )
                }
            }
        }
    }

    pendingUninstall?.let { app ->
        UninstallConfirmDialog(
            app = app,
            onConfirm = {
                viewModel.uninstallApp(context, app.packageName)
                pendingUninstall = null
            },
            onDismiss = { pendingUninstall = null },
        )
    }
}

@Composable
private fun ShizukuStatusBanner(
    available: Boolean,
    granted: Boolean,
    onRequestPermission: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (!available) "Shizuku not running" else "Permission required",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = if (!available)
                        "Start Shizuku (via wireless ADB or root) to enable silent installs"
                    else
                        "Grant ChudStore access to Shizuku to continue",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (available && !granted) {
                Button(onClick = onRequestPermission) {
                    Text("Grant")
                }
            }
        }
    }
}

@Composable
private fun AppCard(
    app: InstalledApp,
    onDeleteClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), // плоские карточки — iOS не использует material elevation shadows
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                bitmap = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() },
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)), // squircle-приближение — настоящий squircle потребовал бы кастомного Shape, отдельная фича, не критично для v1
            )

            Spacer(modifier = Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = "${app.packageName}  ·  v${app.versionName ?: "?"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }

            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Uninstall ${app.label}",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun UninstallConfirmDialog(
    app: InstalledApp,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uninstall ${app.label}?") },
        text = { Text("This will remove the app and its data. This action can't be undone.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Uninstall")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
