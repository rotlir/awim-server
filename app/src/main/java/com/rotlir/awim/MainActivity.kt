package com.rotlir.awim

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.text.isDigitsOnly
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.rotlir.awim.ui.theme.AppTheme


class MainActivity : ComponentActivity() {
    private var permissionsChecked by mutableStateOf(false)
    private var connectedToWiFi by mutableStateOf(false)
    private var ip: ByteArray? by mutableStateOf(null)
    private var networkCallbackRegistered by mutableStateOf(false)
    private var udpRunning by mutableStateOf(false)
    private var autoAssignPort by mutableStateOf(true)
    private var port by mutableIntStateOf(0)
    private var bindError by mutableStateOf(false)
    private var service: UDPService? by mutableStateOf(null)
    private var binder: UDPService.LocalBinder? by mutableStateOf(null)

    private val serviceCallback = object : ServiceCallback {
        override fun onPortChanged(p: Int) {
            port = p
        }

        override fun onBindErrorChanged(bindErr: Boolean) {
            bindError = bindErr
        }

        override fun onPermissionsOkChanged(permOk: Boolean) {
            permissionsChecked = permOk
        }

        override fun onUdpRunningChanged(isUdpRunning: Boolean) {
            udpRunning = isUdpRunning
        }

    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {
            binder = p1 as UDPService.LocalBinder
            service = binder!!.getService()
            binder!!.registerCallback(serviceCallback)
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            binder = null
            service = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binder?.unregisterCallback(serviceCallback)
        if (binder != null)
            unbindService(serviceConnection)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val bindIntent = Intent(this, UDPService::class.java)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (permissionsChecked) {
                        if (!networkCallbackRegistered) {
                            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                                    connectedToWiFi = capabilities.hasTransport(TRANSPORT_WIFI)
                                }

                                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                                    for (linkAddr in linkProperties.linkAddresses) {
                                        if (linkAddr.address is java.net.Inet4Address) {
                                            ip = linkAddr.address.address
                                            return
                                        }
                                    }
                                    ip = null
                                }
                            })
                            networkCallbackRegistered = true
                        }
                        AppLayout(this)
                        binder?.triggerCallbacks()
                    }
                    else {
                        CheckPermission()
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    fun CheckPermission() {
        val audioPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
        var notificationPermissionState: PermissionState? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            notificationPermissionState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)

        when {
            audioPermissionState.status.isGranted && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> {
                permissionsChecked = true
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notificationPermissionState?.status?.isGranted ?: false && audioPermissionState.status.isGranted -> {
                permissionsChecked = true
            }
            else -> {
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 123)
                    } else {
                        audioPermissionState.launchPermissionRequest()
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (!audioPermissionState.status.isGranted) {
                        val titleText: String
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            titleText = "This app requires microphone permission and notification permission for the service to work properly"
                        } else {
                            titleText = "This app requires microphone permission"
                        }
                        Text(text = titleText,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(16.dp))
                    }

                    val context = LocalContext.current
                    Button(modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                        onClick = {
                            val intent =
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            startActivity(intent)
                        }) {
                        Text(text = "Go to settings")
                    }
                }
            }
        }
    }

    private fun ByteArray.toIPString(): String {
        return joinToString(".") {(it.toInt() and 0xFF).toString()}
    }

    private fun destroyService(context: Context) {
        if (service != null) {
            Log.d("MainActivity", "stopping service from main activity")
            val stopIntent = Intent(context, UDPService::class.java)
            stopService(stopIntent)
            unbindService(serviceConnection)
            binder = null
            service = null
        }
    }

    @Composable
    fun AppLayout(context: Context) {
        var portStr by remember { mutableStateOf("") }
        var statusText by remember { mutableStateOf("") }
        var btnText by remember { mutableStateOf("Start AWiM") }
        var enableTextField by remember { mutableStateOf(true) }
        var enableButton by remember { mutableStateOf(true) }

        enableButton = connectedToWiFi

        LaunchedEffect(bindError) {
            if (connectedToWiFi && bindError) {
                enableButton = true
                enableTextField = true
                btnText = "Start AWiM"
                statusText = "Failed binding socket to specified port.\n You can leave the text field empty to assign port automatically."
            }
        }

        LaunchedEffect(connectedToWiFi) {
            if (!connectedToWiFi) {
                destroyService(context)
                enableButton = false
                enableTextField = false
                btnText = "Start AWiM"
                statusText = "You should connect to WiFi before starting AWiM."
            } else {
                enableButton = true
                enableTextField = true
                statusText = ""
            }
        }

        LaunchedEffect(udpRunning, port) {
            if (udpRunning) {
                statusText = "AWiM running on ${ip?.toIPString() ?: "null"}:${port}"
                enableTextField = false
                btnText = "Stop AWiM"
                portStr = port.toString()
            } else {
                if (!bindError && connectedToWiFi) {
                    statusText = ""
                    enableButton = true
                    enableTextField = true
                    btnText = "Start AWiM"
                }
                destroyService(context)
            }
        }

        Column(Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextField(
                value = portStr,
                label = {Text("Port")},
                enabled = enableTextField,
                onValueChange = {
                    if (it.isDigitsOnly() && it != "") {
                        if (it.toInt() <= 65535) {
                            portStr = it
                            port = portStr.toInt()
                            autoAssignPort = false
                        }
                    }
                    else {
                        portStr = ""
                        autoAssignPort = true
                        port = 0
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Button(enabled = enableButton,
                modifier = Modifier.padding(16.dp),
                onClick = {
                    Log.d("applayout", "onclick")
                    if (!enableButton) return@Button
                    if ((ip?.toIPString() ?: "null") == "null") {
                        statusText = "Error launching AWiM. Make sure you're connected to a WiFi network"
                        destroyService(context)
                    }
                    if (udpRunning) destroyService(context)
                    else {
                        Log.d("applayout", "starting service")
                        val startIntent = Intent(context, UDPService::class.java)
                        startIntent.putExtra("port", port)
                        startForegroundService(startIntent)
                        bindService(startIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                    }
                }) {
                Text(btnText)
                Log.d("AppLayout()", "udpRunning: $udpRunning")

            }
            Text(textAlign = TextAlign.Center, text = statusText)
        }
    }
}
