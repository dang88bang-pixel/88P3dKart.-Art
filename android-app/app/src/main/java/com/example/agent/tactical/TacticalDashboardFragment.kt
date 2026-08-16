package com.example.agent.tactical

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.agent.R
import kotlinx.coroutines.launch

/**
 * Taktisches Dashboard für Einsatzkräfte
 * - Echtzeit-Übersicht aller Personnel
 * - Stress-Level und Einsatzbereitschaft
 * - Alarmliste
 * - Einsatzsteuerung (Start/Stopp)
 */
class TacticalDashboardFragment : Fragment() {

    private var _binding: com.example.agent.databinding.FragmentTacticalDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var tacticalHealth: TacticalHealthMonitoring
    private lateinit var personnelAdapter: PersonnelAdapter
    private lateinit var alertAdapter: AlertAdapter

    // Real active medical service (BLE/UART driver will be plugged in production)
    private val realMedicalService: MedicalMonitoringService = RealMedicalMonitoringService()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Note: In a full project you'd have a layout XML.
        // For integration we create a minimal programmatic UI or assume layout exists.
        _binding = try {
            com.example.agent.databinding.FragmentTacticalDashboardBinding.inflate(inflater, container, false)
        } catch (e: Exception) {
            // Fallback: create a simple view if layout not present
            null
        }
        return _binding?.root ?: View(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tacticalHealth = TacticalHealthMonitoring(realMedicalService)

        setupRecyclerViews()
        setupControls()
        observeData()

        // Register the physical device as the primary operator (real hardware identity)
        lifecycleScope.launch {
            val serial = android.os.Build.SERIAL ?: ("CT45P-" + System.currentTimeMillis())
            tacticalHealth.registerPersonnel(
                TacticalHealthMonitoring.TacticalPersonnel(
                    name = "CT45P-Operator",
                    callSign = serial.take(16),
                    role = TacticalHealthMonitoring.TacticalRole.ASSAULT,
                    heartRate = 78,
                    hrv = 48f
                )
            )
        }
    }

    private fun setupRecyclerViews() {
        if (_binding == null) return

        personnelAdapter = PersonnelAdapter(
            onItemClick = { personnel ->
                showPersonnelDetail(personnel)
            }
        )
        binding.rvPersonnel?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = personnelAdapter
            setHasFixedSize(true)
        }

        alertAdapter = AlertAdapter(
            onAcknowledge = { alert ->
                acknowledgeAlert(alert)
            }
        )
        binding.rvAlerts?.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = alertAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupControls() {
        if (_binding == null) return

        binding.btnStartOperation?.setOnClickListener {
            showOperationDialog()
        }
        
        binding.btnStopOperation?.setOnClickListener {
            stopOperation()
        }

        binding.btnExportReport?.setOnClickListener {
            exportReport()
        }

        binding.btnRefresh?.setOnClickListener {
            refreshData()
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            tacticalHealth.personnel.collect { personnel ->
                personnelAdapter.submitList(personnel)
                updateStats(personnel)
            }
        }

        lifecycleScope.launch {
            tacticalHealth.alerts.collect { alert ->
                alertAdapter.addAlert(alert)
                showAlertNotification(alert)
            }
        }

        lifecycleScope.launch {
            tacticalHealth.operationActive.collect { active ->
                binding.btnStartOperation?.isEnabled = !active
                binding.btnStopOperation?.isEnabled = active
                binding.tvOperationStatus?.text = if (active) "🟢 Laufend" else "⏸️ Inaktiv"
            }
        }
    }

    private fun updateStats(personnel: List<TacticalHealthMonitoring.TacticalPersonnel>) {
        if (_binding == null) return
        val total = personnel.size
        val operational = personnel.count { it.status == TacticalHealthMonitoring.PersonnelStatus.OPERATIONAL }
        val casualty = personnel.count { it.status == TacticalHealthMonitoring.PersonnelStatus.CASUALTY || it.status == TacticalHealthMonitoring.PersonnelStatus.KIA }
        val avgReadiness = if (personnel.isNotEmpty()) personnel.map { it.combatReadiness }.average().toFloat() else 1f

        binding.tvStats?.text = """
            👤 Personal: $total
            ✅ Einsatzbereit: $operational
            🚑 Verletzt: $casualty
            ⚡ Bereitschaft: ${String.format("%.0f", avgReadiness * 100)}%
        """.trimIndent()
    }

    private fun showAlertNotification(alert: TacticalHealthMonitoring.TacticalAlert) {
        if (_binding == null) return
        if (alert.severity >= TacticalHealthMonitoring.AlertSeverity.WARNING) {
            Toast.makeText(requireContext(), alert.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showPersonnelDetail(personnel: TacticalHealthMonitoring.TacticalPersonnel) {
        val msg = """
            ${personnel.callSign} – ${personnel.name}
            Rolle: ${personnel.role}
            HR: ${personnel.heartRate} bpm | HRV: ${personnel.hrv} ms
            SpO2: ${personnel.spo2}% | Temp: ${personnel.temperature}°C
            Status: ${personnel.status}
            Bereitschaft: ${(personnel.combatReadiness * 100).toInt()}%
            Stress: ${personnel.stressLevel}
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Personal-Details")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun acknowledgeAlert(alert: TacticalHealthMonitoring.TacticalAlert) {
        lifecycleScope.launch {
            tacticalHealth.acknowledgeAlert(alert.id)
        }
    }

    private fun showOperationDialog() {
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "Einsatzname"
            setText("OP-TACTICAL-2026")
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Einsatz starten")
            .setView(editText)
            .setPositiveButton("Start") { _, _ ->
                val name = editText.text.toString().ifBlank { "Einsatz" }
                lifecycleScope.launch {
                    tacticalHealth.startOperation(name)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun stopOperation() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Einsatz beenden?")
            .setPositiveButton("Ja") { _, _ ->
                lifecycleScope.launch {
                    tacticalHealth.stopOperation()
                }
            }
            .setNegativeButton("Nein", null)
            .show()
    }

    private fun exportReport() {
        lifecycleScope.launch {
            val reports = tacticalHealth.reports.value
            if (reports.isNotEmpty()) {
                val latest = reports.last()
                val reportText = tacticalHealth.exportReport(latest.operationId)
                
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, reportText)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Taktischer Einsatzbericht")
                }
                startActivity(android.content.Intent.createChooser(intent, "Bericht exportieren"))
            } else {
                Toast.makeText(requireContext(), "Kein Bericht verfügbar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshData() {
        lifecycleScope.launch {
            tacticalHealth.checkAllPersonnel() // triggers re-evaluation from current real data
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// Minimal adapters for the dashboard (can be expanded)
class PersonnelAdapter(
    private val onItemClick: (TacticalHealthMonitoring.TacticalPersonnel) -> Unit
) : androidx.recyclerview.widget.ListAdapter<
    TacticalHealthMonitoring.TacticalPersonnel,
    PersonnelAdapter.ViewHolder
>(object : androidx.recyclerview.widget.DiffUtil.ItemCallback<TacticalHealthMonitoring.TacticalPersonnel>() {
    override fun areItemsTheSame(old: TacticalHealthMonitoring.TacticalPersonnel, new: TacticalHealthMonitoring.TacticalPersonnel) = old.id == new.id
    override fun areContentsTheSame(old: TacticalHealthMonitoring.TacticalPersonnel, new: TacticalHealthMonitoring.TacticalPersonnel) = old == new
}) {
    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val name: android.widget.TextView = view.findViewById(android.R.id.text1) ?: android.widget.TextView(view.context)
        val details: android.widget.TextView = view.findViewById(android.R.id.text2) ?: android.widget.TextView(view.context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = android.widget.LinearLayout(parent.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            addView(android.widget.TextView(context).apply { id = android.R.id.text1; textSize = 16f })
            addView(android.widget.TextView(context).apply { id = android.R.id.text2; textSize = 12f; setTextColor(0x888888) })
        }
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = getItem(position)
        holder.name.text = "${p.callSign} • ${p.name} (${p.role})"
        holder.details.text = "HR ${p.heartRate} | Stress ${p.stressLevel} | ${ (p.combatReadiness*100).toInt() }%"
        holder.itemView.setOnClickListener { onItemClick(p) }
    }
}

class AlertAdapter(
    private val onAcknowledge: (TacticalHealthMonitoring.TacticalAlert) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<AlertAdapter.ViewHolder>() {
    private val alerts = mutableListOf<TacticalHealthMonitoring.TacticalAlert>()

    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val text: android.widget.TextView = view.findViewById(android.R.id.text1) ?: android.widget.TextView(view.context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = android.widget.TextView(parent.context).apply {
            setPadding(24, 8, 24, 8)
            textSize = 13f
        }
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val a = alerts[position]
        holder.text.text = "${a.severity} • ${a.message}"
        holder.itemView.setOnClickListener { onAcknowledge(a) }
    }

    override fun getItemCount() = alerts.size

    fun addAlert(alert: TacticalHealthMonitoring.TacticalAlert) {
        alerts.add(0, alert)
        if (alerts.size > 12) alerts.removeLast()
        notifyDataSetChanged()
    }
}