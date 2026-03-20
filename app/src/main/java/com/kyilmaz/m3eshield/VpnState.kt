package com.kyilmaz.m3eshield

import android.content.Context
import androidx.compose.runtime.mutableStateOf

enum class DnsProvider(val displayName: String, val primaryIp: String, val secondaryIp: String) {
    ADGUARD("AdGuard DNS (Ad & Tracker Blocker)", "94.140.14.14", "94.140.15.15"),
    QUAD9("Quad9 (Malware & Phishing Blocker)", "9.9.9.9", "149.112.112.112"),
    CLOUDFLARE("Cloudflare (Fast & Private)", "1.1.1.1", "1.0.0.1"),
    GOOGLE("Google Public DNS (Standard)", "8.8.8.8", "8.8.4.4")
}

object VpnState {
    val isVpnEnabled = mutableStateOf(false)
    val activeDnsProvider = mutableStateOf(DnsProvider.ADGUARD)

    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("m3eshield_prefs", Context.MODE_PRIVATE)
        val providerName = prefs.getString("dns_provider", DnsProvider.ADGUARD.name)
        activeDnsProvider.value = try {
            DnsProvider.valueOf(providerName ?: DnsProvider.ADGUARD.name)
        } catch (e: Exception) {
            DnsProvider.ADGUARD
        }
    }

    fun saveSettings(context: Context, provider: DnsProvider) {
        activeDnsProvider.value = provider
        context.getSharedPreferences("m3eshield_prefs", Context.MODE_PRIVATE).edit()
            .putString("dns_provider", provider.name)
            .apply()
    }
}
