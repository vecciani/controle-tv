package com.controletv.phone

import dadb.Dadb
import java.io.File

/**
 * Installs APKs on a TV or TV box over ADB in the way that works on the most devices: copy the
 * file over first, then install it from there (granting its permissions). Slow boxes can take
 * minutes on a big app, so callers use a connection with a long read timeout.
 */
object AdbInstall {
    private const val REMOTE = "/data/local/tmp/controle-tv-install.apk"

    fun install(adb: Dadb, files: List<File>) {
        // Google's check of apps installed over ADB blocks many APKs on TV boxes
        runCatching { adb.shell("settings put global verifier_verify_adb_installs 0") }
        if (files.size > 1) {
            adb.installMultiple(files, "-r", "-g")
            return
        }
        adb.push(files[0], REMOTE, 420 /* 0644 */, System.currentTimeMillis())
        try {
            val out = adb.shell("pm install -r -g $REMOTE").allOutput.trim()
            if (!out.contains("Success")) throw IllegalStateException(out.lines().lastOrNull { it.isNotBlank() } ?: "sem resposta")
        } finally {
            runCatching { adb.shell("rm -f $REMOTE") }
        }
    }

    /** What went wrong, in words, for the usual install errors. */
    fun explain(label: String, reason: String): String = when {
        "NO_MATCHING_ABIS" in reason -> "$label não é compatível com o processador deste aparelho (procure a versão armeabi-v7a ou universal)"
        "OLDER_SDK" in reason -> "$label precisa de um Android mais novo que o deste aparelho"
        "UPDATE_INCOMPATIBLE" in reason -> "Já existe outra versão de $label com assinatura diferente. Desinstale ela antes"
        "INSUFFICIENT_STORAGE" in reason -> "Falta espaço no aparelho para instalar $label"
        "VERIFICATION" in reason -> "O aparelho bloqueou $label (verificação de apps). Desative \"Verificar apps\" nas configurações de segurança dele"
        "INVALID_APK" in reason || "NOT_APK" in reason || "PARSE_FAILED" in reason -> "O arquivo de $label está incompleto ou não é um APK"
        "timed out" in reason || "Timeout" in reason -> "O aparelho demorou demais para responder. Tente de novo com ele parado na tela inicial"
        else -> "Falhou: $reason"
    }
}
