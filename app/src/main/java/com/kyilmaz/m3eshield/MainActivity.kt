package com.kyilmaz.m3eshield

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyilmaz.m3eshield.ui.theme.M3EShieldTheme

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress

class MainActivity : ComponentActivity() {

    private val vpnRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            // User denied VPN permission
            VpnState.isVpnEnabled.value = false
        }
    }

    private val notificationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // We just continue whether granted or not.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        VpnState.loadSettings(this)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionRequest.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            M3EShieldTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        isVpnEnabled = VpnState.isVpnEnabled.value,
                        onToggle = { enable ->
                            if (enable) {
                                val intent = VpnService.prepare(this)
                                if (intent != null) {
                                    vpnRequest.launch(intent)
                                } else {
                                    startVpnService()
                                }
                            } else {
                                stopVpnService()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(isVpnEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    var showDevMenu by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var titleClickCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val activeProvider by VpnState.activeDnsProvider
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                titleClickCount++
                                if (titleClickCount >= 7) {
                                    showDevMenu = true
                                    titleClickCount = 0
                                }
                                scope.launch {
                                    delay(2000)
                                    titleClickCount = 0
                                }
                            })
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "M3E Shield",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.5).sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsMenu = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { /* Account profile placeholder */ }) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "Account", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            val useRow = maxWidth > 600.dp || maxWidth > maxHeight

            if (useRow) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShieldIcon(isVpnEnabled = isVpnEnabled)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        StatusSection(isVpnEnabled, onToggle)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    ShieldIcon(isVpnEnabled = isVpnEnabled)
                    Spacer(modifier = Modifier.height(48.dp))
                    StatusSection(isVpnEnabled, onToggle)
                }
            }

            val context = LocalContext.current
            val packageInfo = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                } catch (e: Exception) {
                    null
                }
            }
            val versionName = packageInfo?.versionName ?: "1.0"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo?.longVersionCode?.toString() ?: "1"
            } else {
                @Suppress("DEPRECATION")
                packageInfo?.versionCode?.toString() ?: "1"
            }

            Text(
                text = "M3E Shield v$versionName ($versionCode)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            titleClickCount++
                            if (titleClickCount >= 7) {
                                showDevMenu = true
                                titleClickCount = 0
                            }
                            scope.launch {
                                delay(2000)
                                titleClickCount = 0
                            }
                        })
                    }
            )
        }

        if (showSettingsMenu) {
            DnsSettingsDialog(
                currentProvider = activeProvider,
                onDismiss = { showSettingsMenu = false },
                onProviderSelected = { provider ->
                    VpnState.saveSettings(context, provider)
                    showSettingsMenu = false
                    // If VPN is currently active, we need to restart it to apply the new DNS
                    if (isVpnEnabled) {
                        onToggle(false)
                        scope.launch {
                            delay(500)
                            onToggle(true)
                        }
                    }
                }
            )
        }

        if (showDevMenu) {
            DeveloperBottomSheet(
                isVpnEnabled = isVpnEnabled,
                onToggleVpn = onToggle,
                onDismiss = { showDevMenu = false }
            )
        }
    }
}

@Composable
fun StatusSection(isVpnEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    AnimatedContent(
        targetState = isVpnEnabled,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                    scaleIn(initialScale = 0.92f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
                .togetherWith(fadeOut(animationSpec = tween(90)))
        }, label = "TextAnimation"
    ) { targetExpanded ->
        Text(
            text = if (targetExpanded) "Protected" else "Unprotected",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Powered by ${VpnState.activeDnsProvider.value.displayName.substringBefore(" (")}",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(48.dp))

    // A Material 3 interactive toggle
    FilledTonalIconToggleButton(
        checked = isVpnEnabled,
        onCheckedChange = onToggle,
        modifier = Modifier.size(80.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Security,
            contentDescription = "Toggle VPN",
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
fun ShieldIcon(isVpnEnabled: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isVpnEnabled) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseScale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isVpnEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "backgroundColor"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isVpnEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iconColor"
    )

    Box(
        modifier = Modifier
            .size(200.dp)
            .graphicsLayer {
                val scale = if (isVpnEnabled) pulseScale else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = isVpnEnabled,
            transitionSpec = {
                scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) togetherWith 
                scaleOut(animationSpec = spring(stiffness = Spring.StiffnessMedium))
            }, label = "IconAnimation"
        ) { active ->
            Icon(
                imageVector = if (active) Icons.Filled.Shield else Icons.Outlined.Shield,
                contentDescription = "Shield Icon",
                modifier = Modifier.size(100.dp),
                tint = iconColor
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperBottomSheet(
    isVpnEnabled: Boolean,
    onToggleVpn: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var stressTestActive by remember { mutableStateOf(false) }
    var blockTestResult by remember { mutableStateOf("") }
    var pingTestResult by remember { mutableStateOf("") }

    // Live memory monitor
    var memUsage by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val runtime = Runtime.getRuntime()
            val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val max = runtime.maxMemory() / (1024 * 1024)
            memUsage = "$used MB / $max MB"
            delay(1000)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Developer Tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            
            HorizontalDivider()

            Text("Heap Usage: $memUsage", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)

            Button(
                onClick = {
                    if (!stressTestActive) {
                        stressTestActive = true
                        scope.launch {
                            var currentToggleState = isVpnEnabled
                            repeat(10) {
                                currentToggleState = !currentToggleState
                                onToggleVpn(currentToggleState)
                                delay(400) // Fast enough to stress test but prevents instant Crashlytics triggers
                            }
                            stressTestActive = false
                        }
                    }
                },
                enabled = !stressTestActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (stressTestActive) "Stress Testing (Watch the UI/Logcat)..." else "UI & Service Stress Test (Rapid Toggle)")
            }

            Button(
                onClick = {
                    blockTestResult = "Testing..."
                    scope.launch(Dispatchers.IO) {
                        try {
                            val start = System.currentTimeMillis()
                            val address = InetAddress.getByName("googleadservices.com")
                            val time = System.currentTimeMillis() - start
                            if (address.hostAddress == "0.0.0.0" || address.hostAddress == "127.0.0.1" || address.hostAddress == "::1" || address.hostAddress == "::") {
                                blockTestResult = "Success: Domain blocked (Resolved to ${address.hostAddress})"
                            } else {
                                blockTestResult = "Failed: Domain resolved to ${address.hostAddress} in ${time}ms (Ad blocking isn't active)"
                            }
                        } catch (e: Exception) {
                            blockTestResult = "Success: Domain blocked (UnknownHostException)"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Ad Domain Resolution Test")
            }
            if (blockTestResult.isNotEmpty()) {
                Text(
                    text = blockTestResult, 
                    style = MaterialTheme.typography.bodySmall, 
                    color = if (blockTestResult.startsWith("Success")) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = {
                    pingTestResult = "Pinging..."
                    scope.launch(Dispatchers.IO) {
                        try {
                            val start = System.currentTimeMillis()
                            InetAddress.getByName("dns.adguard-dns.com")
                            val time = System.currentTimeMillis() - start
                            pingTestResult = "Latency to AdGuard DNS: ${time}ms"
                        } catch (e: Exception) {
                            pingTestResult = "Error resolving DNS server"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Check AdGuard DNS Latency")
            }
            if (pingTestResult.isNotEmpty()) {
                Text(pingTestResult, style = MaterialTheme.typography.bodySmall)
            }
            
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun DnsSettingsDialog(
    currentProvider: DnsProvider,
    onDismiss: () -> Unit,
    onProviderSelected: (DnsProvider) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select DNS Provider") },
        text = {
            Column {
                DnsProvider.values().forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { onProviderSelected(provider) })
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentProvider == provider,
                            onClick = { onProviderSelected(provider) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(provider.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
