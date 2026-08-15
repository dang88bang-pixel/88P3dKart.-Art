package com.example.agent.ui.bluetooth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.agent.MainActivity
import com.example.agent.R
import com.example.agent.bluetooth.BluetoothAccessory
import com.example.agent.bluetooth.BluetoothAccessoryType
import kotlinx.coroutines.launch

/**
 * Fragment für Bluetooth-Zubehör Übersicht im CT45P
 * Zeigt alle erkannten Geräte nach Typ gruppiert, Health, Batterie, RSSI
 */
class BluetoothAccessoryFragment : Fragment() {

    private lateinit var statusText: TextView
    private lateinit var listText: TextView
    private lateinit var healthText: TextView

    private var accessoryMap: Map<String, BluetoothAccessory> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_bluetooth, container, false)
        statusText = view.findViewById(R.id.bt_status)
        listText = view.findViewById(R.id.bt_list)
        healthText = view.findViewById(R.id.bt_health)

        view.findViewById<View>(R.id.btn_scan_high_accuracy)?.setOnClickListener {
            (activity as? MainActivity)?.let { main ->
                // Zugriff über bleManager
                // main.bleManager.bluetoothAccessoryManager.startScan(HIGH_ACCURACY)
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Wenn MainActivity die Accessories exposed, collect
        val main = activity as? MainActivity
        val bleManager = main?.let {
            try {
                val field = it.javaClass.getDeclaredField("bleManager")
                field.isAccessible = true
                field.get(it) as? com.example.agent.sensors.BleTokenManager
            } catch (_: Exception) { null }
        }

        lifecycleScope.launch {
            bleManager?.bluetoothAccessoryManager?.accessories?.collect { list ->
                accessoryMap = list.associateBy { it.macAddress }
                updateUI(list)
            }
        }

        lifecycleScope.launch {
            bleManager?.bluetoothAccessoryManager?.getHealthMonitor()?.healthStates?.collect { healthMap ->
                updateHealth(healthMap.values.toList())
            }
        }
    }

    private fun updateUI(list: List<BluetoothAccessory>) {
        activity?.runOnUiThread {
            val byType = list.groupBy { it.type }
            val summary = byType.map { "${it.key.name}: ${it.value.size}" }.joinToString(" | ")
            statusText.text = "📡 ${list.size} Geräte erkannt\n$summary"

            val sorted = list.sortedByDescending { it.rssi }
            val text = sorted.joinToString("\n\n") { acc ->
                buildString {
                    append("${iconForType(acc.type)} ${acc.name} (${acc.type.name})\n")
                    append("MAC: ${acc.macAddress} RSSI: ${acc.rssi} dBm Dist: ${"%.1f".format(acc.estimatedDistanceM ?: 0f)}m\n")
                    append("🔋 ${acc.batteryLevel}% ${if (acc.isMoving) "🚶MOVING" else "🧍"} ${if (acc.isSosActive) "🚨SOS" else ""}\n")
                    acc.temperatureC?.let { append("🌡️ $it°C ") }
                    acc.humidityPct?.let { append("💧 $it% ") }
                    acc.heartRateBpm?.let { append("❤️ $it bpm ") }
                    acc.steps?.let { append("👣 $it ") }
                    if (acc.iBeaconUuid != null) append("\n📦 iBeacon ${acc.iBeaconMajor}/${acc.iBeaconMinor}")
                    if (acc.eddystoneUrl != null) append("\n🔗 ${acc.eddystoneUrl}")
                }
            }
            listText.text = if (text.isEmpty()) "Keine Geräte – Scan läuft..." else text
        }
    }

    private fun updateHealth(healthList: List<com.example.agent.bluetooth.AccessoryHealthMonitor.AccessoryHealth>) {
        activity?.runOnUiThread {
            val healthy = healthList.count { it.status == "HEALTHY" }
            val degraded = healthList.count { it.status == "DEGRADED" }
            val critical = healthList.count { it.status == "CRITICAL" }
            val lost = healthList.count { it.status == "LOST" }
            healthText.text = "🟢 Healthy: $healthy | 🟡 Degraded: $degraded | 🔴 Critical: $critical | ⚪ Lost: $lost\n\n" +
                healthList.filter { it.warnings.isNotEmpty() }.joinToString("\n") { h ->
                    "⚠️ ${h.mac} ${h.status} ${h.warnings.joinToString(",")} Score=${"%.2f".format(h.score)}"
                }
        }
    }

    private fun iconForType(type: BluetoothAccessoryType): String = when (type) {
        BluetoothAccessoryType.TOKEN_CLASSIC, BluetoothAccessoryType.TOKEN_PRO -> "🔑"
        BluetoothAccessoryType.SENSOR_TAG -> "🌡️"
        BluetoothAccessoryType.WEARABLE -> "⌚"
        BluetoothAccessoryType.ASSET_TAG -> "📦"
        BluetoothAccessoryType.REMOTE_CONTROLLER -> "🎮"
        BluetoothAccessoryType.RELAY -> "📱"
        BluetoothAccessoryType.GATEWAY_BRIDGE -> "🌉"
        BluetoothAccessoryType.HEADSET -> "🎧"
        BluetoothAccessoryType.HID -> "⌨️"
        else -> "📡"
    }
}
