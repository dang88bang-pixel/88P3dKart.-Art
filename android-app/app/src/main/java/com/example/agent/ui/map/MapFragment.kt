package com.example.agent.ui.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.agent.R

class MapFragment : Fragment() {
    private val renderer = MapRenderer()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_map, container, false)
        val mapView: View = view.findViewById(R.id.map_canvas_view)

        mapView.post {
            val canvas = mapView.holder?.lockCanvas()
            if (canvas != null) {
                renderer.draw(canvas, mapView.width, mapView.height)
                mapView.holder.unlockCanvasAndPost(canvas)
            }
        }
        return view
    }
}
