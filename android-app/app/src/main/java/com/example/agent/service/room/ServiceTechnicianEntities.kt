package com.example.agent.service.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import java.util.UUID

/**
 * Service / Techniker Datenbank (Room)
 * 
 * Ergänzt die Geräte-Datenbank um:
 * - Techniker-Profile
 * - Service-Tickets
 * - Repair-Logs (mit Verknüpfung zu Device)
 * 
 * Echtzeit-fähig + Audit-fähig.
 */

@Entity(tableName = "technician_profiles")
data class TechnicianProfile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val callSign: String,
    val company: String = "Honeywell / Partner",
    val certifications: List<String> = emptyList(),
    val contact: String = "",
    val lastActive: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

@Entity(
    tableName = "service_tickets",
    foreignKeys = [
        ForeignKey(
            entity = TechnicianProfile::class,
            parentColumns = ["id"],
            childColumns = ["assignedTechnicianId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("assignedTechnicianId"), Index("deviceId")]
)
data class ServiceTicket(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val title: String,
    val description: String,
    val status: TicketStatus = TicketStatus.OPEN,
    val priority: Int = 2, // 1=high, 2=medium, 3=low
    val assignedTechnicianId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    val notes: String = ""
)

enum class TicketStatus { OPEN, IN_PROGRESS, WAITING_PARTS, REPAIRED, CLOSED }

@Entity(
    tableName = "repair_logs",
    foreignKeys = [
        ForeignKey(
            entity = ServiceTicket::class,
            parentColumns = ["id"],
            childColumns = ["ticketId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ticketId"), Index("deviceId")]
)
data class RepairLog(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val ticketId: String,
    val deviceId: String,
    val technicianId: String,
    val action: String,                    // e.g. "FRP_BYPASS", "UART_REPAIR", "BLE_RESET", "FIRMWARE_FLASH"
    val details: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val data: Map<String, String> = emptyMap()   // e.g. {"uart_command":"AT+FRP=1"}
)