package com.controletv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Experiment: can this Fire TV wake another HDMI device (the Xbox) over HDMI-CEC?
 *
 * Regular apps can't send CEC, but the phone's ADB connection runs as the shell user, which
 * may call the system's HDMI service directly ("service call hdmi_control"). The method numbers
 * differ between systems, so they're read from the system's own classes. Only methods that
 * take plain numbers are ever called (ones with callbacks could crash the system service).
 */
object CecProbe {
    private const val STUB = "android.hardware.hdmi.IHdmiControlService\$Stub"
    private const val IFACE = "android.hardware.hdmi.IHdmiControlService"

    data class Attempt(val id: String, val label: String, val command: String)

    /** Method name → call number of the HDMI service on this TV. */
    private fun codes(): Map<String, Int> = runCatching {
        Class.forName(STUB).declaredFields.filter { it.name.startsWith("TRANSACTION_") }.associate { f ->
            f.isAccessible = true
            f.name.removePrefix("TRANSACTION_") to f.getInt(null)
        }
    }.getOrDefault(emptyMap())

    private fun signatures(): Map<String, String> = runCatching {
        Class.forName(IFACE).declaredMethods.associate { m -> m.name to m.parameterTypes.joinToString(", ") { it.simpleName } }
    }.getOrDefault(emptyMap())

    /** Ways to wake a device that this system offers, for each place the Xbox could be. */
    fun attempts(): List<Attempt> {
        val codes = codes()
        val out = mutableListOf<Attempt>()
        // Android 10+ calls (Amazon may have them on Fire OS 7): power on a device by its CEC
        // address (players use 4, 8 or 11), or ask the device on an HDMI port to show itself
        codes["powerOnRemoteDevice"]?.let { code ->
            for (address in listOf(4, 8, 11)) {
                out += Attempt("pon$address", "Ligar o aparelho no endereço $address", "service call hdmi_control $code i32 $address i32 1")
            }
        }
        codes["askRemoteDeviceToBecomeActiveSource"]?.let { code ->
            for (port in 1..4) {
                out += Attempt("src$port", "Chamar o aparelho da HDMI $port (a TV pode trocar para ela)", "service call hdmi_control $code i32 ${port * 0x1000}")
            }
        }
        return out
    }

    /** Everything the HDMI side looks like from here, for the phone's diagnostics. */
    fun report(): JSONObject {
        val codes = codes()
        val signatures = signatures()
        fun sh(command: String) = runCatching { Adb.shell(command).trim() }.getOrElse { "erro: ${it.message}" }
        // Getters that take nothing are safe to call, and show whether the shell may use the service
        val getters = listOf("getSupportedTypes", "getPhysicalAddress", "getActiveSource", "getPortInfo", "getDeviceList", "getInputDevices")
        val calls = getters.mapNotNull { name -> codes[name]?.let { "$name → " + sh("service call hdmi_control $it").take(600) } }
        return JSONObject()
            .put("onScreen", when (HdmiInput.fireTvOnScreen()) { true -> "sim"; false -> "não (outra entrada HDMI)"; null -> "não dá para saber" })
            .put("methods", JSONArray(codes.entries.sortedBy { it.value }.map { (name, code) -> "$code  $name(${signatures[name] ?: "?"})" }))
            .put("attempts", JSONArray(attempts().map { JSONObject().put("id", it.id).put("label", it.label) }))
            .put("calls", JSONArray(calls))
            .put("service", sh("service list | grep -i -E 'hdmi|cec'"))
            .put("cmd", sh("cmd hdmi_control help 2>&1 | head -20"))
            .put("props", sh("getprop | grep -i -E 'cec|hdmi' | head -40"))
            .put("nodes", sh("ls -l /dev/cec* /sys/class/cec 2>&1 | head -10"))
            .put("dumpsys", sh("dumpsys hdmi_control 2>&1 | head -150").take(9000))
    }

    fun attempt(id: String): String {
        val attempt = attempts().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Tentativa desconhecida")
        return attempt.command + "\n" + Adb.shell(attempt.command).trim()
    }
}
