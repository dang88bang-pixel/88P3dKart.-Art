package com.example.agent.ui.live

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.agent.MainActivity
import com.example.agent.R
import com.example.agent.health.WorkloadMode
import kotlinx.coroutines.launch

class LiveViewFragment : Fragment() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: PointCloudRenderer
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_live, container, false)
        glSurfaceView = view.findViewById(R.id.gl_surface_view)
        renderer = PointCloudRenderer()
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        glSurfaceView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    renderer.rotate(dx / glSurfaceView.width, dy / glSurfaceView.height)
                    glSurfaceView.requestRender()
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            true
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as MainActivity
        val statusView = view.findViewById<TextView>(R.id.tv_status)
        val modeView = view.findViewById<TextView>(R.id.tv_mode)

        mainActivity.webSocketClient.onBinaryPointCloud = { binary ->
            PointCloudFrameDecoder.decode(binary)?.let { points ->
                activity?.runOnUiThread {
                    renderer.updatePointCloud(points)
                    glSurfaceView.requestRender()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainActivity.deviceHealthState.collect { health ->
                    glSurfaceView.renderMode = if (health.workloadMode == WorkloadMode.NORMAL) {
                        GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    } else {
                        GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    }
                    statusView.text = getString(
                        R.string.device_health_status,
                        health.thermalStatus.name,
                        health.batteryTemperatureC?.let { "%.1f °C".format(it) } ?: "–",
                        health.batteryPercent?.let { "%.0f %%".format(it) } ?: "–",
                    )
                    modeView.text = getString(
                        R.string.device_workload_mode,
                        health.workloadMode.name,
                    )
                    glSurfaceView.requestRender()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::glSurfaceView.isInitialized) glSurfaceView.onResume()
    }

    override fun onPause() {
        if (::glSurfaceView.isInitialized) glSurfaceView.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.webSocketClient?.onBinaryPointCloud = null
        super.onDestroyView()
    }
}
