package com.adtech.flashmessage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.Normalizer

class MainActivity : Activity() {

    private lateinit var cm: CameraManager
    private var camId: String? = null
    @Volatile private var busy = false
    @Volatile private var stop = false
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var lamp: TextView
    private lateinit var received: TextView
    private lateinit var diag: TextView
    private lateinit var recvBtn: Button

    private val unit = 250L

    private val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val codes = ".- -... -.-. -.. . ..-. --. .... .. .--- -.- .-.. -- -. --- .--. --.- .-. ... - ..- ...- .-- -..- -.-- --.. ----- .---- ..--- ...-- ....- ..... -.... --... ---.. ----.".split(" ")

    // Réception
    private var recvOn = false
    private var camDev: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lo = 0f
    private var hi = 0f
    private var warm = 0
    private var diagCount = 0
    private var curOn = false
    private var started = false
    private var lastChangeMs = 0L
    private var receiving = false
    private var lampShown = false
    private val letterBuf = StringBuilder()
    private val msg = StringBuilder()

    private fun label(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.parseColor("#FF8A3D"))
        v.textSize = 14f
        v.setPadding(0, 48, 0, 8)
        return v
    }

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
        root.setPadding(48, 80, 48, 48)

        val title = TextView(this)
        title.text = "AD TECH · Flash Message"
        title.setTextColor(Color.WHITE)
        title.textSize = 22f
        root.addView(title)

        root.addView(label("ENVOYER"))

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

        root.addView(label("RECEVOIR"))

        recvBtn = Button(this)
        recvBtn.text = "RECEVOIR"
        recvBtn.setOnClickListener {
            if (recvOn) {
                stopReceive()
                status.text = "Réception arrêtée"
            } else {
                startReceive()
            }
        }
        root.addView(recvBtn)

        lamp = TextView(this)
        lamp.text = "●"
        lamp.textSize = 56f
        lamp.gravity = Gravity.CENTER
        lamp.setTextColor(Color.DKGRAY)
        root.addView(lamp)

        received = TextView(this)
        received.setTextColor(Color.WHITE)
        received.textSize = 22f
        root.addView(received)

        diag = TextView(this)
        diag.setTextColor(Color.GRAY)
        diag.textSize = 12f
        root.addView(diag)

        status = TextView(this)
        status.setTextColor(Color.LTGRAY)
        status.textSize = 16f
        status.setPadding(0, 32, 0, 0)
        status.text = if (camId == null) "Pas de flash sur ce téléphone" else "Prêt"
        root.addView(status)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#0D0D10"))
        sv.isFillViewport = true
        sv.addView(root)
        setContentView(sv)
    }

    override fun onPause() {
        stop = true
        if (recvOn) stopReceive()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startReceive()
        } else {
            status.text = "Permission caméra refusée"
        }
    }

    // ---------------- ENVOI ----------------

    private fun send(text: String) {
        val id = camId
        if (id == null) {
            status.text = "Pas de flash sur ce téléphone"
            return
        }
        if (recvOn) {
            status.text = "Arrête la réception d'abord"
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

    // ---------------- RÉCEPTION ----------------

    private fun ui(t: String) {
        runOnUiThread { status.text = t }
    }

    private fun show(text: String, st: String) {
        runOnUiThread {
            received.text = text
            status.text = st
        }
    }

    private fun resetDecoder() {
        warm = 0
        diagCount = 0
        lo = 0f
        hi = 0f
        curOn = false
        started = false
        receiving = false
        lampShown = false
        letterBuf.setLength(0)
        msg.setLength(0)
    }

    private fun startReceive() {
        val id = camId
        if (id == null) {
            status.text = "Pas de caméra avec flash"
            return
        }
        if (busy) {
            status.text = "Attends la fin de l'envoi"
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
            return
        }
        if (recvOn) return
        recvOn = true
        recvBtn.text = "ARRÊTER LA RÉCEPTION"
        status.text = "Vise le flash de l'autre téléphone"
        received.text = ""
        resetDecoder()

        val t = HandlerThread("recv")
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h
        try {
            val chars = cm.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.filter { it.width >= 320 && it.height >= 240 }
                ?.minByOrNull { it.width * it.height } ?: Size(640, 480)
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                try {
                    val img = rd.acquireLatestImage()
                    if (img != null) {
                        try {
                            onFrame(img)
                        } finally {
                            img.close()
                        }
                    }
                } catch (e: Exception) {
                }
            }, h)
            openCam(id, chars)
        } catch (e: Exception) {
            status.text = "Erreur caméra : " + e.message
            stopReceive()
        }
    }

    @Suppress("MissingPermission")
    private fun openCam(id: String, chars: CameraCharacteristics) {
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (!recvOn) {
                    d.close()
                    return
                }
                camDev = d
                makeSession(d, chars)
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                ui("Erreur caméra : $error")
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private fun makeSession(d: CameraDevice, chars: CameraCharacteristics) {
        val r = reader ?: return
        try {
            d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!recvOn) return
                    session = s
                    try {
                        val rq = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        rq.addTarget(r.surface)
                        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                        if (range != null) {
                            rq.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, range.lower)
                        }
                        s.setRepeatingRequest(rq.build(), null, handler)
                    } catch (e: Exception) {
                        ui("Erreur : " + e.message)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    ui("Caméra : échec de configuration")
                }
            }, handler)
        } catch (e: Exception) {
            ui("Erreur : " + e.message)
        }
    }

    private fun stopReceive() {
        recvOn = false
        try {
            session?.close()
        } catch (e: Exception) {
        }
        session = null
        try {
            camDev?.close()
        } catch (e: Exception) {
        }
        camDev = null
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
        recvBtn.text = "RECEVOIR"
        lamp.setTextColor(Color.DKGRAY)
    }

    // Luminosité du point le plus lumineux (moyenne de blocs 4x4)
    private fun peakOf(img: Image): Int {
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val w = img.width
        val h = img.height
        var best = 0
        var by = 0
        while (by + 4 <= h) {
            var bx = 0
            while (bx + 4 <= w) {
                var sum = 0
                for (y in 0 until 4) {
                    val base = (by + y) * rs + bx * ps
                    for (x in 0 until 4) {
                        sum += buf.get(base + x * ps).toInt() and 0xFF
                    }
                }
                if (sum > best) best = sum
                bx += 4
            }
            by += 4
        }
        return best / 16
    }

    private fun onFrame(img: Image) {
        val peak = peakOf(img).toFloat()
        val t = img.timestamp / 1000000L
        if (warm < 25) {
            warm++
            lo = peak
            hi = peak
            return
        }
        if (peak > hi) hi = peak else hi += (peak - hi) * 0.002f
        if (peak < lo) lo = peak else lo += (peak - lo) * 0.002f
        val contrast = hi - lo
        val on = if (contrast < 30f) {
            false
        } else if (curOn) {
            peak > lo + contrast * 0.4f
        } else {
            peak > lo + contrast * 0.6f
        }
        if (on != lampShown) {
            lampShown = on
            runOnUiThread { lamp.setTextColor(if (on) Color.YELLOW else Color.DKGRAY) }
        }
        diagCount++
        if (diagCount % 10 == 0) {
            val txt = "lumière " + peak.toInt() + " · min " + lo.toInt() + " · max " + hi.toInt()
            runOnUiThread { diag.text = txt }
        }
        step(on, t)
    }

    private fun step(on: Boolean, t: Long) {
        if (!started) {
            started = true
            curOn = on
            lastChangeMs = t
            return
        }
        if (on != curOn) {
            val d = t - lastChangeMs
            if (curOn) onFlash(d) else onGap(d)
            curOn = on
            lastChangeMs = t
        } else if (!on && receiving && t - lastChangeMs > unit * 14) {
            finishMsg()
        }
    }

    private fun onFlash(d: Long) {
        if (d >= unit * 5) {
            if (!receiving) {
                receiving = true
                letterBuf.setLength(0)
                msg.setLength(0)
                show("…", "Message détecté, réception…")
            } else {
                finishMsg()
            }
        } else if (receiving) {
            if (d >= unit * 2) {
                letterBuf.append('-')
            } else if (d >= unit * 2 / 5) {
                letterBuf.append('.')
            }
        }
    }

    private fun onGap(d: Long) {
        if (!receiving) return
        if (d >= unit * 2) {
            flushLetter()
            if (d >= unit * 5 && msg.isNotEmpty() && msg[msg.length - 1] != ' ') {
                msg.append(' ')
            }
            show(msg.toString(), "Réception…")
        }
    }

    private fun flushLetter() {
        if (letterBuf.isEmpty()) return
        val idx = codes.indexOf(letterBuf.toString())
        msg.append(if (idx >= 0) letters[idx] else '?')
        letterBuf.setLength(0)
    }

    private fun finishMsg() {
        flushLetter()
        receiving = false
        val m = msg.toString().trim()
        show(m, "Message reçu ✔")
    }
}
