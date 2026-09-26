package com.selfcontrol.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gate_events")
data class GateEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val eventTime: Long,
    val reason: String?,
    val action: String
)
