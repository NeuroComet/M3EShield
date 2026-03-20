package com.kyilmaz.m3eshield

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

class AdBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    private var dnsForwarder: DnsForwarder? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_CHANNEL_ID = "m3eshield_vpn_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_START -> startVpn()
                ACTION_STOP -> stopVpn()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "M3E Shield Status"
            val descriptionText = "Displays the current status of the AdBlocker VPN"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        createNotificationChannel()

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("M3E Shield Active")
            .setContentText("DNS traffic is currently being filtered.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startVpn() {
        if (vpnInterface != null) {
            return
        }

        try {
            startForegroundService()

            val prefs = getSharedPreferences("m3eshield_prefs", Context.MODE_PRIVATE)
            val providerName = prefs.getString("dns_provider", DnsProvider.ADGUARD.name)
            val provider = try { DnsProvider.valueOf(providerName ?: DnsProvider.ADGUARD.name) } catch (e: Exception) { DnsProvider.ADGUARD }

            val builder = Builder()
                .setSession("M3E Shield VPN")
                .addDnsServer(provider.primaryIp)
                .addDnsServer(provider.secondaryIp)
                // Route the DNS server IPs so they actually get intercepted by our DnsForwarder!
                .addRoute(provider.primaryIp, 32)
                .addRoute(provider.secondaryIp, 32)
                // A dummy IPv4 for the VPN interface
                .addAddress("10.0.0.2", 32)
                // A dummy IPv6 address for the VPN interface
                .addAddress("fd00::1", 128)
            // Actually, Android adds DNS routes automatically.
            
            val iface = builder.establish()
            vpnInterface = iface

            if (iface != null) {
                dnsForwarder = DnsForwarder(this, iface.fileDescriptor)
                dnsForwarder?.start()
            }

            // Update UI/State
            VpnState.isVpnEnabled.value = true
            
        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun stopVpn() {
        try {
            dnsForwarder?.stop()
            dnsForwarder = null
            vpnInterface?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            vpnInterface = null
            VpnState.isVpnEnabled.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
