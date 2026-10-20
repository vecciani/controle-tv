package com.controletv

/**
 * Whether the TV is showing this Fire TV right now, or another HDMI input (a console, a cable
 * box…). Apps can't ask the HDMI service directly, but its state can be read through the
 * phone's ADB connection ("dumpsys hdmi_control"), which keeps track of the active source.
 */
object HdmiInput {
    /** true = the TV shows the Fire TV, false = another input, null = can't tell (no ADB, no HDMI-CEC…). */
    fun fireTvOnScreen(): Boolean? {
        if (Adb.state != Adb.State.OK) return null
        val dump = runCatching { Adb.shell("dumpsys hdmi_control") }.getOrNull() ?: return null
        return parse(dump)
    }

    /** Reads the active source from the HDMI service's dump (layouts differ between Android versions). */
    fun parse(dump: String): Boolean? {
        // Android 9 players: "mIsActiveSource: true"
        Regex("mIsActiveSource\\s*[:=]\\s*(true|false)", RegexOption.IGNORE_CASE).findAll(dump).lastOrNull()?.let {
            return it.groupValues[1].equals("true", ignoreCase = true)
        }
        // Newer layout: "mActiveSource: (0x4, 0x2000)" compared with our own physical address
        val own = Regex("mPhysicalAddress\\s*[:=]\\s*0x([0-9a-fA-F]{1,4})").find(dump)?.groupValues?.get(1)?.toInt(16)
        val active = Regex("mActiveSource\\s*[:=]\\s*\\(\\s*0x[0-9a-fA-F]+\\s*,\\s*0x([0-9a-fA-F]{1,4})\\s*\\)").find(dump)?.groupValues?.get(1)?.toInt(16)
        if (own != null && active != null && active != 0xFFFF) return own == active
        return null
    }
}
