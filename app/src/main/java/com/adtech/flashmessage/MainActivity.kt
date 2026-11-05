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

    // Durée d'un "point" en millisecondes (plus grand = plus lent)
    private val unit = 250L

    private val morse = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
