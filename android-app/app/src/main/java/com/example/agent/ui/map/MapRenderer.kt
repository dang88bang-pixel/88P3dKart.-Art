package com.example.agent.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.agent.sensors.BleTokenManager

/** Top-down map for measurements that actually provide spatial coordinates. */
class MapRenderer {
    private val paintTarget = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
    private val paintUser = Paint().apply { color = Color.BLUE; style = Paint.Style.FILL }
    private val paintGrid = Paint().apply { color = Color.GRAY; strokeWidth = 1f }
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
        val centerX = width / 2f
        val centerY = height / 2f
        val scale = 20f

        for (meter in -10..10 step 2) {
            canvas.drawLine(
                centerX + meter * scale,
                0f,
                centerX + meter * scale,
                height.toFloat(),
                paintGrid,
            )
            canvas.drawLine(
                0f,
                centerY + meter * scale,
                width.toFloat(),
                centerY + meter * scale,
                paintGrid,
            )
        }

        canvas.drawCircle(centerX + userX * scale, centerY + userY * scale, 12f, paintUser)
        canvas.drawText(
            "Device",
            centerX + userX * scale + 16,
            centerY + userY * scale + 8,
            paintText,
        )

        targets.forEach { (x, y, _) ->
            canvas.drawCircle(centerX + x * scale, centerY + y * scale, 8f, paintTarget)
        }

        // One RSSI observation cannot determine range or bearing. Keep tokens in
        // an explicit unlocated list instead of inventing map coordinates.
        tokens.take(MAX_UNLOCATED_TOKENS).forEachIndexed { index, token ->
            val battery = token.battery?.let { "$it%" } ?: "unknown battery"
            canvas.drawText(
                "Unlocated ${token.mac}: ${token.rssi} dBm, $battery",
                16f,
                30f + index * 24f,
                paintText,
            )
        }
    }

    companion object {
        private const val MAX_UNLOCATED_TOKENS = 8
    }
}
