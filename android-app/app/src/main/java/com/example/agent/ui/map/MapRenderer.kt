package com.example.agent.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.agent.sensors.BleTokenManager
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** 2D-Kartenansicht (Top-Down): BLE-Token, mmWave-Targets, eigene Position. */
class MapRenderer {

    private val paintToken = Paint().apply { color = Color.GREEN; style = Paint.Style.FILL }
    private val paintTarget = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
    private val paintUser = Paint().apply { color = Color.BLUE; style = Paint.Style.FILL }
    private val paintText = Paint().apply { color = Color.WHITE; textSize = 20f }

    private var tokens: List<BleTokenManager.TokenData> = emptyList()
    private var targets: List<Triple<Float, Float, Float>> = emptyList()
    private var userX = 0f
    private var userY = 0f

    fun updateData(
        tokens: List<BleTokenManager.TokenData>,
        targets: List<Triple<Float, Float, Float>>,
        userPos: Pair<Float, Float>,
    ) {
        this.tokens = tokens
        this.targets = targets
        this.userX = userPos.first
        this.userY = userPos.second
    }

    fun draw(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.BLACK)
        val cx = width / 2f
        val cy = height / 2f
        val scale = 20f // Pixel pro Meter

        // Gitter
        paintText.color = Color.GRAY
        for (i in -10..10 step 2) {
            canvas.drawLine(cx + i * scale, 0f, cx + i * scale, height.toFloat(), paintText)
            canvas.drawLine(0f, cy + i * scale, width.toFloat(), cy + i * scale, paintText)
        }

        // Benutzer
        canvas.drawCircle(cx + userX * scale, cy + userY * scale, 12f, paintUser)
        paintText.color = Color.WHITE
        canvas.drawText("Ich", cx + userX * scale + 16, cy + userY * scale + 8, paintText)

        // BLE-Token (RSSI → Distanz, Richtung via Triangulation)
        tokens.forEach { token ->
            val dist = 10f.pow((-60 - token.rssi) / 20f)
            val angle = token.mac.hashCode() % 360
            val tx = userX + dist * cos(Math.toRadians(angle.toDouble())).toFloat()
            val ty = userY + dist * sin(Math.toRadians(angle.toDouble())).toFloat()
            canvas.drawCircle(cx + tx * scale, cy + ty * scale, 10f, paintToken)
            canvas.drawText("${token.rssi}dBm", cx + tx * scale + 16, cy + ty * scale + 8, paintText)
        }

        // mmWave-Targets
        targets.forEach { (x, y, _) ->
            canvas.drawCircle(cx + x * scale, cy + y * scale, 8f, paintTarget)
        }
    }
}
