package com.example.agent.ui.live

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** OpenGL ES 2.0 Renderer für die Live-3D-Punktwolke. */
class PointCloudRenderer : GLSurfaceView.Renderer {

    private val vertexShaderCode = """
        attribute vec4 vPosition;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_PointSize = 2.0;
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;
        void main() { gl_FragColor = vColor; }
    """.trimIndent()

    private var program = 0
    private var positionHandle = 0
    private var mvpMatrixHandle = 0
    private var colorHandle = 0

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var vertexBuffer: FloatBuffer? = null
    private var pointCount = 0
    private val color = floatArrayOf(0f, 1f, 0f, 1f)

    private var angleX = 0f
    private var angleY = 0f
    private var distance = -20f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.15f, 1f)
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs); GLES20.glLinkProgram(it)
        }
        positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        colorHandle = GLES20.glGetUniformLocation(program, "vColor")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 3f, 30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setLookAtM(
            viewMatrix, 0,
            distance * sin(Math.toRadians(angleY.toDouble())).toFloat(),
            distance * sin(Math.toRadians(angleX.toDouble())).toFloat(),
            distance * cos(Math.toRadians(angleY.toDouble())).toFloat(),
            0f, 0f, 0f, 0f, 1f, 0f
        )
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)

        vertexBuffer?.let { buf ->
            if (pointCount > 0) {
                buf.position(0)
                GLES20.glEnableVertexAttribArray(positionHandle)
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, buf)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)
                GLES20.glDisableVertexAttribArray(positionHandle)
            }
        }
    }

    fun updatePointCloud(points: FloatArray) {
        pointCount = points.size / 3
        val bb = ByteBuffer.allocateDirect(points.size * 4).order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer().apply { put(points); position(0) }
    }

    fun rotate(dx: Float, dy: Float) {
        angleX += dy * 0.5f
        angleY += dx * 0.5f
    }

    fun zoom(delta: Float) {
        distance = (distance + delta).coerceIn(-40f, -5f)
    }

    private fun loadShader(type: Int, code: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code); GLES20.glCompileShader(it)
        }
}
