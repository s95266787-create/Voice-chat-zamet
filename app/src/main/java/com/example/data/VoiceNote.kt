package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_notes")
data class VoiceNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val transcription: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val isTranscribing: Boolean = false,
    val transcriptionError: String? = null
)
