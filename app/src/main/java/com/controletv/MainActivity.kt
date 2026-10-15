package com.controletv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/** The TV screen: shows the QR code and PIN the phone uses to connect. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ControlService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on every resume because the IP can change after a Wi-Fi reconnect
        render()
    }

    private fun render() {
        val pin = Pairing(this).pin
        val ip = localIp()
        val url = ip?.let { "http://$it:${ControlServer.PORT}" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        root.addView(text("Controle TV", 44f, "#F8FAFC", bold = true))

        if (url == null) {
            root.addView(text("Sem conexão de rede. Conecte o Fire TV ao Wi-Fi.", 24f, "#FCA5A5"))
        } else {
            root.addView(text("Aponte a câmera do celular para o código", 22f, "#94A3B8"))
            root.addView(ImageView(this).apply {
                setImageBitmap(qr("$url/?pin=$pin", 440))
                setPadding(0, 32, 0, 32)
            })
            root.addView(text(url, 22f, "#94A3B8"))
            root.addView(text("PIN  $pin", 36f, "#38BDF8", bold = true))
        }
        setContentView(root)
    }

    private fun text(value: String, size: Float, color: String, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 8)
        }

    private fun qr(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2)
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress

    companion object {
        fun open(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
