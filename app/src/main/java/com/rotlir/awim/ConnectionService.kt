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
import android.media.AudioRecord.READ_BLOCKING
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

interface ServiceCallback {
    fun onPortChanged(p: Int)
    fun onBindErrorChanged(bindErr: Boolean)
    fun onPermissionsOkChanged(permOk: Boolean)
    fun onRunningChanged(isRunning: Boolean)
}

class ConnectionService() : Service() {
    inner class LocalBinder: Binder() {
        fun getService() : ConnectionService {
            return this@ConnectionService
        }

        fun isTCPMode() : Boolean = tcpMode

        fun registerCallback(callback: ServiceCallback) {
            callbacks.add(callback)
        }

        fun unregisterCallback(callback: ServiceCallback) {
            callbacks.remove(callback)
        }

        fun triggerCallbacks() {
            triggerPortCallback()
            triggerRunningCallback()
        }
    }
    private var bindError = false
    @Volatile
    private var running = false
    private var autoAssignPort = false
    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: ServerSocket? = null
    private var audioRecord: AudioRecord? = null
    private var port: Int = 0
    private val binder = LocalBinder()
    private var thread: Thread? = null
    private var permissionsOk = true
    private val callbacks = mutableListOf<ServiceCallback>()
    private var wakelock: PowerManager.WakeLock? = null
    private var tcpMode = false

    private fun triggerPortCallback() {
        for (callback in callbacks) callback.onPortChanged(port)
    }

    private fun triggerBindErrorCallback() {
        for (callback in callbacks) callback.onBindErrorChanged(bindError)
    }

    private fun triggerPermissionsCallback() {
        for (callback in callbacks) callback.onPermissionsOkChanged(permissionsOk)
    }

    private fun triggerRunningCallback() {
        for (callback in callbacks) callback.onRunningChanged(running)
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
        tcpMode = intent?.getBooleanExtra("tcpMode", false) ?: false
        if (port == 0) autoAssignPort = true
        thread = if (tcpMode) Thread {tcp()}
        else Thread {udp()}
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
        running = false
        audioRecord?.stop()
        audioRecord?.release()
        udpSocket?.close()
        tcpSocket?.close()
        wakelock?.release()
        thread?.join()
        triggerRunningCallback()
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d("onBind()", "bound")
        return binder
    }

    private fun tcp() {
        bindError = false
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
            tcpSocket = if (autoAssignPort) ServerSocket(0)
            else ServerSocket(port)
        } catch (e: IOException) {
            bindError = true
            triggerBindErrorCallback()
            stopSelf()
            return
        }
        port = tcpSocket!!.localPort
        triggerPortCallback()
        running = true
        triggerRunningCallback()
        var dataInputStream: InputStream? = null
        var dataOutputStream: OutputStream? = null
        var clientSocket: Socket? = null
        tcpSocket!!.soTimeout = 1000;
        while (true) {
            try {
                clientSocket = tcpSocket!!.accept()
                Log.d("tcp", "accepted client")
                dataInputStream = clientSocket.getInputStream()
                dataOutputStream = clientSocket.getOutputStream()
                break
            } catch (e: SocketTimeoutException) {
                if (!running) {
                    try {
                        clientSocket?.close()
                    } catch (_: Exception) {}
                    dataInputStream?.close()
                    dataOutputStream?.close()
                    return
                } else {
                    continue
                }
            } catch (e: IOException) {
                e.printStackTrace()
                dataInputStream?.close()
                dataOutputStream?.close()
                stopSelf()
                return
            }
        }

        audioRecord!!.startRecording()
        fun stop() {
            dataInputStream?.close()
            dataOutputStream?.close()
        }
        while (true) {
            if (!running) {
                stop()
                return
            }
            try {
                val dataSize = ByteArray(4)
                dataInputStream!!.read(dataSize)
                val amount = (dataSize[0].toInt() and 0xff) or
                        ((dataSize[1].toInt() and 0xff) shl 8) or
                        ((dataSize[2].toInt() and 0xff) shl 16) or
                        ((dataSize[3].toInt()) shl 24)
                val buf = ByteArray(amount)
                audioRecord!!.read(buf, 0, amount, AudioRecord.READ_BLOCKING)
                dataOutputStream!!.write(buf)
            } catch (e: Exception) {
                e.printStackTrace()
                stop()
                stopSelf()
                return
            }
        }


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
            udpSocket = if (autoAssignPort) DatagramSocket()
            else DatagramSocket(port)
        } catch (e: SocketException) {
            Log.d("udp()", "failed to bind udpSocket")
            bindError = true
            triggerBindErrorCallback()
            stopSelf()
            return
        }
        port = udpSocket!!.localPort
        triggerPortCallback()
        udpSocket!!.soTimeout = 5000
        Log.d("udp()", "listening on port ${udpSocket!!.localPort}")
        port = udpSocket!!.localPort

        fun stop() {
            udpSocket!!.close()
            running = false
            triggerRunningCallback()
        }

        running = true
        triggerRunningCallback()
        while (true) {
            if (!running) {
                stop()
                return
            }
            // this variable stores the amount of data we need to provide
            // server sends it as an unsigned int which takes 4 bytes in C
            val dataSize = ByteArray(4)
            val sizePacket = DatagramPacket(dataSize, dataSize.size)

            try {
                udpSocket!!.receive(sizePacket)
            } catch (e: SocketTimeoutException) {
                if (!firstReceived) continue
                else {
                    stop()
                    stopSelf()
                    return
                }
            } catch (e: Exception) {
                if (!running) return
            }
            if (!firstReceived) {
                audioRecord!!.startRecording()
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
                udpSocket!!.send(packet)
            } catch (e: Exception) {
                if (!running) return
            }
            //Log.d("udp()", "end")
        }

    }
}