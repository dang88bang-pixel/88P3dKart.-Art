package com.example.agent.ui.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceHolder
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
        // fragment_map.xml definiert eine SurfaceView mit id map_canvas_view.
        // Vorher wurde das Ergebnis fälschlich als generische View gehalten —
        // View.holder existiert nicht (das schlug als Compile-Fehler fehl).
        val mapView = view.findViewById<SurfaceView>(R.id.map_canvas_view)

        mapView.post {
            val holder: SurfaceHolder = mapView.holder
            val canvas = holder.lockCanvas() ?: return@post
            try {
                renderer.draw(canvas, mapView.width, mapView.height)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        return view
    }
}
