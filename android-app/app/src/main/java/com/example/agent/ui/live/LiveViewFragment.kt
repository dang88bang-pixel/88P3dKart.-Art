package com.example.agent.ui.live

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.agent.MainActivity

class LiveViewFragment : Fragment() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: PointCloudRenderer
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(com.example.agent.R.layout.fragment_live, container, false)
        glSurfaceView = view.findViewById(com.example.agent.R.id.gl_surface_view)
        renderer = PointCloudRenderer()
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        glSurfaceView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x; lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    renderer.rotate(dx / glSurfaceView.width, dy / glSurfaceView.height)
                    lastTouchX = event.x; lastTouchY = event.y
                }
            }
            true
        }

        val ws = (activity as MainActivity).webSocketClient
        ws.onBinaryPointCloud = { binary ->
            PointCloudFrameDecoder.decode(binary)?.let { points ->
                activity?.runOnUiThread { renderer.updatePointCloud(points) }
            }
        }
        return view
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.webSocketClient?.onBinaryPointCloud = null
        super.onDestroyView()
    }
}
