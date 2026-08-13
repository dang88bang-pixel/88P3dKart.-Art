package com.example.agent.ui.scenario

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.agent.R
import com.example.agent.network.AgentApiClient
import kotlinx.coroutines.launch

class ScenarioFragment : Fragment() {
    private lateinit var spinnerScenario: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private val apiClient = AgentApiClient()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_scenario, container, false)
        spinnerScenario = view.findViewById(R.id.spinner_scenario)
        btnStart = view.findViewById(R.id.btn_start)
        btnStop = view.findViewById(R.id.btn_stop)

        val adapter = ArrayAdapter.createFromResource(
            requireContext(), R.array.scenario_types, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerScenario.adapter = adapter

        btnStart.setOnClickListener {
            val type = spinnerScenario.selectedItem.toString().lowercase()
            val params = mapOf("persons" to 50, "smoke" to 0.7)
            lifecycleScope.launch { apiClient.startScenario(type, params) }
        }
        btnStop.setOnClickListener { /* Szenario stoppen (REST) */ }
        return view
    }
}
