package com.example.agent.service.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServiceTechnicianDao {

    // === Technician Profiles ===
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTechnician(technician: TechnicianProfile)

    @Query("SELECT * FROM technician_profiles WHERE isActive = 1 ORDER BY name")
    fun getActiveTechnicians(): Flow<List<TechnicianProfile>>

    @Query("SELECT * FROM technician_profiles WHERE id = :id")
    suspend fun getTechnicianById(id: String): TechnicianProfile?

    // === Service Tickets ===
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTicket(ticket: ServiceTicket)

    @Query("SELECT * FROM service_tickets WHERE deviceId = :deviceId ORDER BY createdAt DESC")
    fun getTicketsForDevice(deviceId: String): Flow<List<ServiceTicket>>

    @Query("SELECT * FROM service_tickets WHERE status IN ('OPEN', 'IN_PROGRESS') ORDER BY priority, createdAt")
    fun getOpenTickets(): Flow<List<ServiceTicket>>

    @Update
    suspend fun updateTicket(ticket: ServiceTicket)

    // === Repair Logs ===
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepairLog(log: RepairLog)

    @Query("SELECT * FROM repair_logs WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    fun getRepairHistoryForDevice(deviceId: String): Flow<List<RepairLog>>

    @Query("SELECT * FROM repair_logs WHERE ticketId = :ticketId ORDER BY timestamp DESC")
    suspend fun getLogsForTicket(ticketId: String): List<RepairLog>

    // Cleanup (1 year retention)
    @Query("DELETE FROM repair_logs WHERE timestamp < :cutoff")
    suspend fun deleteOldRepairLogs(cutoff: Long)
}