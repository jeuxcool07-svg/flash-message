package com.adtech.flashmessage

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.Normalizer

class MainActivity : Activity() {

    private lateinit var cm: CameraManager
    private var camId: String? = null
    @Volatile private var busy = false
    @Volatile private var stop = false
    private lateinit var status: TextView
    private lateinit var input: EditText

    private val unit = 250L

    private val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val codes = ".- -... -.-. -.. . ..-. --. .... .. .--- -.- .-.. -- -. --- .--. --.- .-. ... - ..- ...- .-- -..- -.-- --.. ----- .---- ..--- ...-- ....- ..... -.... --... ---.. ----.".split(" ")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                val hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val back = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                if (hasFlash && back) {
                    camId = id
                    break
                }
            }
        } catch (e: Exception) {
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0D0D10"))
        root.setPadding(48, 96, 48, 48)

        val title = TextView(this)
        title.text = "AD TECH · Flash Message"
        title.setTextColor(Color.WHITE)
        title.textSize = 22f
        title.setPadding(0, 0, 0, 32)
        root.addView(title)

        input = EditText(this)
        input.hint = "Ton message"
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.GRAY)
        root.addView(input)

        val sendBtn = Button(this)
        sendBtn.text = "ENVOYER"
        sendBtn.setOnClickListener { send(input.text.toString()) }
        root.addView(sendBtn)

        val stopBtn = Button(this)
        stopBtn.text = "STOP"
        stopBtn.setOnClickListener { stop = true }
        root.addView(stopBtn)

        status = TextView(this)
        status.setTextColor(Color.LTGRAY)
        status.textSize = 16f
        status.setPadding(0, 32, 0, 0)
        status.text = if (camId == null) "Pas de flash sur ce téléphone" else "Prêt"
        root.addView(status)

        setContentView(root)
    }

    override fun onPause() {
        stop = true
        super.onPause()
    }

    private fun send(text: String) {
        val id = camId
        if (id == null) {
            status.text = "Pas de flash sur ce téléphone"
            return
        }
        if (busy) return
        val clean = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .uppercase()
            .trim()
        if (clean.isEmpty()) {
            status.text = "Écris un message d'abord"
            return
        }
        busy = true
        stop = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Envoi en cours…"

        Thread {
            try {
                flash(id, true)
                pause(unit * 7)
                flash(id, false)
                pause(unit * 7)

                val words = clean.split(Regex("\\s+"))
                for ((wi, w) in words.withIndex()) {
                    for ((ci, ch) in w.withIndex()) {
                        val idx = letters.indexOf(ch)
                        if (idx < 0) continue
                        val code = codes[idx]
                        for ((si, s) in code.withIndex()) {
                            if (stop) return@Thread
                            flash(id, true)
                            pause(if (s == '.') unit else unit * 3)
                            flash(id, false)
                            if (si < code.length - 1) pause(unit)
                        }
                        if (ci < w.length - 1) pause(unit * 3)
                    }
                    if (wi < words.size - 1) pause(unit * 7)
                }

                pause(unit * 7)
                flash(id, true)
                pause(unit * 7)
            } finally {
                flash(id, false)
                busy = false
                runOnUiThread {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    status.text = "Terminé"
                }
            }
        }.start()
    }

    private fun flash(id: String, on: Boolean) {
        try {
            cm.setTorchMode(id, on)
        } catch (e: Exception) {
        }
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }
}
