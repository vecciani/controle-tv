package com.controletv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * The projector's own controls: brightness and Bluetooth (speakers, headphones). Brightness is
 * Android's screen brightness, which on these projectors drives the lamp; it's changed through
 * the phone's ADB connection (or directly, when the app may write system settings).
 */
object Projector {

    fun status(context: Context): JSONObject = JSONObject()
        .put("brightness", brightnessPercent(context))
        .put("bluetooth", bluetooth())

    // ---------- Brightness ----------

    fun brightnessPercent(context: Context): Int {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return (raw * 100 / 255).coerceIn(0, 100)
    }

    /** Sets the brightness from 5 to 100 % (never fully dark, or the picture would disappear). */
    fun setBrightness(context: Context, percent: Int): Int {
        val value = (percent.coerceIn(5, 100) * 255 / 100).coerceIn(1, 255)
        if (Settings.System.canWrite(context)) {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        } else {
            if (Adb.state != Adb.State.OK) throw IllegalStateException("O brilho precisa do controle completo (ADB) ativo")
            Adb.shell("settings put system screen_brightness_mode 0; settings put system screen_brightness $value")
        }
        return brightnessPercent(context)
    }

    // ---------- Bluetooth ----------

    @SuppressLint("MissingPermission")
    fun bluetooth(): JSONObject {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return JSONObject().put("available", false)
        val on = adapter.isEnabled
        val devices = if (on) adapter.bondedDevices.orEmpty().sortedBy { it.name.orEmpty() }.map { device ->
            JSONObject()
                .put("name", device.name ?: device.address)
                .put("address", device.address)
                .put("kind", kind(device))
                .put("connected", connected(device))
        } else emptyList()
        return JSONObject().put("available", true).put("on", on).put("devices", JSONArray(devices))
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun setBluetooth(on: Boolean) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IllegalStateException("Este aparelho não tem Bluetooth")
        val ok = if (on) adapter.enable() else adapter.disable()
        if (!ok && Adb.state == Adb.State.OK) Adb.shell(if (on) "svc bluetooth enable" else "svc bluetooth disable")
    }

    /** Opens the Bluetooth screen on the projector, to pair or connect a speaker or headphones. */
    fun openBluetoothSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "fone", "caixa de som", "controle"… from the device's class. */
    @SuppressLint("MissingPermission")
    private fun kind(device: BluetoothDevice): String = when (device.bluetoothClass?.majorDeviceClass) {
        android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "Áudio"
        android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> "Controle ou teclado"
        android.bluetooth.BluetoothClass.Device.Major.PHONE -> "Celular"
        android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "Computador"
        else -> "Aparelho"
    }

    /** Whether the device is connected right now (Android only exposes this to apps indirectly). */
    private fun connected(device: BluetoothDevice): Boolean =
        runCatching { device.javaClass.getMethod("isConnected").invoke(device) as Boolean }.getOrDefault(false)
}
