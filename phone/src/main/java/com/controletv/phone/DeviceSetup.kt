package com.controletv.phone

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Puts Controle TV on another Android device on the network (a projector, a TV box): finds the
 * ones with network debugging on (ADB, port 5555) and installs the TV app there. The phone does
 * it because the hotspot only lets the phone reach the other devices.
 */
class DeviceSetup(private val context: Context) {
    private val keys: AdbKeyPair by lazy {
        // Same key as the TV connection, so a device that already trusts this phone won't ask again
        val private = File(context.filesDir, "adbkey")
        val public = File(context.filesDir, "adbkey.pub")
        if (!private.exists()) AdbKeyPair.generate(private, public)
        AdbKeyPair.read(private, public)
    }

    /** Addresses on the phone's networks that answer on the ADB port. */
    fun scan(): JSONArray {
        val own = mutableSetOf<String>()
        val prefixes = mutableSetOf<String>()
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (address in nif.inetAddresses) {
                if (address !is Inet4Address) continue
                own += address.hostAddress!!
                prefixes += address.hostAddress!!.substringBeforeLast('.')
            }
        }
        val pool = Executors.newFixedThreadPool(64)
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        for (prefix in prefixes) for (i in 1..254) {
            val ip = "$prefix.$i"
            if (ip in own) continue
            pool.execute {
                runCatching { Socket().use { it.connect(InetSocketAddress(ip, PORT), 500) }; found += ip }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        return JSONArray(found.sorted())
    }

    /** Model, maker and Android version; the device asks "Allow debugging?" the first time. */
    fun describe(ip: String): JSONObject {
        connect(ip).use { adb ->
            val out = adb.shell(
                "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; pm list packages com.controletv"
            ).allOutput.lines()
            return JSONObject()
                .put("ip", ip)
                .put("maker", out.getOrNull(0)?.trim().orEmpty())
                .put("model", out.getOrNull(1)?.trim().orEmpty())
                .put("android", out.getOrNull(2)?.trim().orEmpty())
                .put("installed", out.any { it.trim() == "package:com.controletv" })
        }
    }

    /** Downloads the latest TV app, installs it on [ip] and opens it there. */
    fun installTvApp(ip: String, progress: (String) -> Unit): String {
        progress("Baixando o Controle TV…")
        val info = JSONObject(TvUpdater.download(BuildConfig.UPDATE_URL).decodeToString())
        val apk = File(context.cacheDir, "controle-tv-device.apk")
        apk.writeBytes(TvUpdater.download(info.getString("apk")))
        try {
            progress("Instalando no aparelho…")
            connect(ip).use { adb ->
                adb.install(apk, "-r")
                adb.shell("am start -n com.controletv/.MainActivity")
            }
        } finally {
            apk.delete()
        }
        return "Controle TV instalado e aberto no aparelho"
    }

    /** Waits up to a minute for the user to accept "Allow debugging?" on the device. */
    private fun connect(ip: String): Dadb {
        if (!Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(ip)) throw IllegalArgumentException("Endereço inválido")
        return Dadb.create(ip, PORT, keys, 3_000, 60_000)
    }

    companion object {
        private const val PORT = 5555
    }
}
