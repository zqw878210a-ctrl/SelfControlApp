package com.selfcontrol.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface GateEventDao {
    @Insert
    fun insertGateEvent(event: GateEvent): Long

    @Query("SELECT * FROM gate_events ORDER BY eventTime DESC, id DESC")
    fun getAllGateEvents(): List<GateEvent>
}
