package com.rotlir.awim

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.PermissionChecker
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException

interface ServiceCallback {
    fun onPortChanged(p: Int)
    fun onBindErrorChanged(bindErr: Boolean)
    fun onPermissionsOkChanged(permOk: Boolean)
    fun onUdpRunningChanged(isUdpRunning: Boolean)
}

class UDPService() : Service() {
    inner class LocalBinder: Binder() {
        fun getService() : UDPService {
            return this@UDPService
        }

        fun registerCallback(callback: ServiceCallback) {
            callbacks.add(callback)
        }

        fun unregisterCallback(callback: ServiceCallback) {
            callbacks.remove(callback)
        }

        fun triggerCallbacks() {
            triggerPortCallback()
            triggerUdpRunningCallback()
        }
    }
    private var bindError = false
    @Volatile
    private var udpRunning = false
    private var autoAssignPort = false
    private var socket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var port: Int = 0
    private val binder = LocalBinder()
    private var thread: Thread? = null
    private var permissionsOk = true
    private val callbacks = mutableListOf<ServiceCallback>()
    private var wakelock: PowerManager.WakeLock? = null

    private fun triggerPortCallback() {
        for (callback in callbacks) callback.onPortChanged(port)
    }

    private fun triggerBindErrorCallback() {
        for (callback in callbacks) callback.onBindErrorChanged(bindError)
    }

    private fun triggerPermissionsCallback() {
        for (callback in callbacks) callback.onPermissionsOkChanged(permissionsOk)
    }

    private fun triggerUdpRunningCallback() {
        for (callback in callbacks) callback.onUdpRunningChanged(udpRunning)
    }

    private fun createNotification(pendingIntent: PendingIntent): Notification {
        val channelId = "AWiMServiceChannel"
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "AWiM service", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AWiM Service")
            .setContentText("AWiM is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startForeground() {
        val microphonePermission = PermissionChecker.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (microphonePermission != PermissionChecker.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = createNotification(pendingIntent)
        ServiceCompat.startForeground(
            this,
            100,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
            )
        Log.d("startForeground()", "starting foreground service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        port = intent?.getIntExtra("port", 0) ?: 0
        if (port == 0) autoAssignPort = true
        thread = Thread {udp()}
        thread!!.start()
        startForeground()
        wakelock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UDPService::lock").apply {
                acquire()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("onDestroy()", "destroying service")
        udpRunning = false
        audioRecord?.stop()
        socket?.close()
        wakelock?.release()
        thread?.join()
        triggerUdpRunningCallback()
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d("onBind()", "bound")
        return binder
    }

    private fun udp() {
        bindError = false
        var firstReceived = false
        val audioSource = MediaRecorder.AudioSource.MIC
        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsOk = false
            triggerPermissionsCallback()
            stopSelf()
            return
        } else
            AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)

        try {
            socket = if (autoAssignPort) DatagramSocket()
            else DatagramSocket(port)
        } catch (e: SocketException) {
            Log.d("udp()", "failed to bind socket")
            bindError = true
            triggerBindErrorCallback()
            stopSelf()
            return
        }
        port = socket!!.localPort
        triggerPortCallback()
        audioRecord!!.startRecording()
        socket!!.soTimeout = 5000
        Log.d("udp()", "listening on port ${socket!!.localPort}")
        port = socket!!.localPort

        fun stop() {
            audioRecord!!.stop()
            socket!!.close()
            udpRunning = false
            triggerUdpRunningCallback()
        }

        udpRunning = true
        triggerUdpRunningCallback()
        while(true) {
            if (!udpRunning) {
                stop()
                return
            }
            // this variable stores the amount of data we need to provide
            // server sends it as an unsigned int which takes 4 bytes in C
            val dataSize = ByteArray(4)
            val sizePacket = DatagramPacket(dataSize, dataSize.size)

            try {
                socket!!.receive(sizePacket)
            } catch (e: SocketTimeoutException) {
                if (!firstReceived) continue
                else {
                    stop()
                    stopSelf()
                    return
                }
            } catch (e: Exception) {
                if (!udpRunning) return
            }
            //Log.d("udp()", "start")
            firstReceived = true
            // convert back to int
            val amount = (dataSize[0].toInt() and 0xff) or
                    ((dataSize[1].toInt() and 0xff) shl 8) or
                    ((dataSize[2].toInt() and 0xff) shl 16) or
                    ((dataSize[3].toInt()) shl 24)
            //Log.d("udp()", "amount: $amount")
            val serverAddress = sizePacket.socketAddress

            val buf = ByteArray(amount)
            try {
                audioRecord!!.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                val packet = DatagramPacket(buf, buf.size, serverAddress)
                socket!!.send(packet)
            } catch (e: Exception) {
                if (!udpRunning) return
            }
            //Log.d("udp()", "end")
        }

    }
}