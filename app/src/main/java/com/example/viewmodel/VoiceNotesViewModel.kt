package com.example.viewmodel

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.VoiceNote
import com.example.dto.Content
import com.example.dto.GenerateContentRequest
import com.example.dto.InlineData
import com.example.dto.Part
import com.example.network.RetrofitClient
import com.example.service.AudioPlayer
import com.example.service.AudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceNotesViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val audioRecorder = AudioRecorder(application)
    private val audioPlayer = AudioPlayer()

    // Recording States
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private var currentRecordingFile: File? = null
    private var recordingJob: Job? = null

    // Playback States
    private val _playingNoteId = MutableStateFlow<Long?>(null)
    val playingNoteId: StateFlow<Long?> = _playingNoteId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private var playbackJob: Job? = null

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Notes List State
    @OptIn(ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<VoiceNote>> = _searchQuery
        .flatMapLatest { query ->
            database.voiceNoteDao().getAllVoiceNotes().map { rawNotes ->
                if (query.isBlank()) {
                    rawNotes
                } else {
                    rawNotes.filter { note ->
                        note.title.contains(query, ignoreCase = true) ||
                                note.transcription.contains(query, ignoreCase = true)
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // --- Recording Management ---

    fun startRecording() {
        if (_isRecording.value) return

        val timestamp = System.currentTimeMillis()
        val file = File(getApplication<Application>().filesDir, "audio_note_$timestamp.m4a")
        currentRecordingFile = file

        _recordingDurationMs.value = 0L
        _isRecording.value = true

        audioRecorder.start(file)

        // Start timer tick
        recordingJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (_isRecording.value) {
                _recordingDurationMs.value = System.currentTimeMillis() - startTime
                delay(100)
            }
        }
    }

    fun stopRecording(customTitle: String? = null) {
        if (!_isRecording.value) return

        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        val finalDurationMs = audioRecorder.stop()
        val recordedFile = currentRecordingFile

        if (recordedFile != null && recordedFile.exists()) {
            val formattedDate = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
            val noteTitle = if (customTitle.isNullOrBlank()) {
                "Аудиозаметка $formattedDate"
            } else {
                customTitle
            }

            val newNote = VoiceNote(
                title = noteTitle,
                filePath = recordedFile.absolutePath,
                durationMs = finalDurationMs
            )

            viewModelScope.launch(Dispatchers.IO) {
                val generatedId = database.voiceNoteDao().insertVoiceNote(newNote)
                val insertedNote = newNote.copy(id = generatedId)
                // Trigger auto transcription
                transcribeVoiceNote(insertedNote)
            }
        }
        currentRecordingFile = null
    }

    // --- Playback Management ---

    fun playAudio(note: VoiceNote) {
        val file = File(note.filePath)
        if (!file.exists()) {
            Log.e("VoiceNotesViewModel", "Audio file not found: ${note.filePath}")
            return
        }

        if (_playingNoteId.value == note.id) {
            // Already active. Toggle.
            if (_isPlaying.value) {
                pauseAudio()
            } else {
                resumeAudio()
            }
            return
        }

        // Stop current before starting another
        stopAudio()

        _playingNoteId.value = note.id
        _isPlaying.value = true

        audioPlayer.play(file) {
            // On Completion
            _isPlaying.value = false
            _playingNoteId.value = null
            _playbackProgress.value = 0f
            _playbackPositionMs.value = 0L
            playbackJob?.cancel()
        }

        startPlaybackProgressTracker()
    }

    fun pauseAudio() {
        if (!_isPlaying.value) return
        _isPlaying.value = false
        audioPlayer.pause()
        playbackJob?.cancel()
    }

    fun resumeAudio() {
        if (_isPlaying.value) return
        _isPlaying.value = true
        audioPlayer.resume()
        startPlaybackProgressTracker()
    }

    fun stopAudio() {
        audioPlayer.stop()
        _isPlaying.value = false
        _playingNoteId.value = null
        _playbackProgress.value = 0f
        _playbackPositionMs.value = 0L
        playbackJob?.cancel()
    }

    fun seekAudio(progress: Float) {
        audioPlayer.seekTo(progress)
        _playbackProgress.value = progress
        _playbackPositionMs.value = (progress * audioPlayer.duration).toLong()
    }

    private fun startPlaybackProgressTracker() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (_isPlaying.value) {
                _playbackProgress.value = audioPlayer.currentProgress
                _playbackPositionMs.value = audioPlayer.currentPosition.toLong()
                delay(100)
            }
        }
    }

    // --- Transcription / API Operations ---

    fun transcribeVoiceNote(note: VoiceNote) {
        viewModelScope.launch(Dispatchers.IO) {
            // Setup loading state
            val updatingNote = note.copy(isTranscribing = true, transcriptionError = null)
            database.voiceNoteDao().updateVoiceNote(updatingNote)

            val apiKey = com.example.BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                database.voiceNoteDao().updateVoiceNote(
                    updatingNote.copy(
                        isTranscribing = false,
                        transcriptionError = "Ключ API не настроен в AI Studio."
                    )
                )
                return@launch
            }

            val file = File(note.filePath)
            if (!file.exists()) {
                database.voiceNoteDao().updateVoiceNote(
                    updatingNote.copy(
                        isTranscribing = false,
                        transcriptionError = "Аудиофайл не существует на устройстве."
                    )
                )
                return@launch
            }

            try {
                val binaryBase64 = file.toBase64()
                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = "Выполни точное и полное распознавание речи (аудио-в-текст) для этого файла на русском языке (или на языке записи, если там другой язык). Выведи исключительно текст транскрипции и ничего больше, без каких-либо твоих комментариев, вступлений или заключений."),
                                Part(inlineData = InlineData(mimeType = "audio/mp4", data = binaryBase64))
                            )
                        )
                    )
                )

                val response = RetrofitClient.service.generateContent(apiKey, request)
                val transResult = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()

                if (!transResult.isNullOrEmpty()) {
                    database.voiceNoteDao().updateVoiceNote(
                        updatingNote.copy(
                            transcription = transResult,
                            isTranscribing = false,
                            transcriptionError = null
                        )
                    )
                } else {
                    database.voiceNoteDao().updateVoiceNote(
                        updatingNote.copy(
                            isTranscribing = false,
                            transcriptionError = "Пустой ответ от Gemini."
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("VoiceNotesViewModel", "Transcription error", e)
                database.voiceNoteDao().updateVoiceNote(
                    updatingNote.copy(
                        isTranscribing = false,
                        transcriptionError = e.localizedMessage ?: "Ошибка связи с сервером AI."
                    )
                )
            }
        }
    }

    // --- Edit and Delete Operations ---

    fun updateNoteTitle(note: VoiceNote, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            database.voiceNoteDao().updateVoiceNote(note.copy(title = newTitle))
        }
    }

    fun deleteVoiceNote(note: VoiceNote) {
        viewModelScope.launch(Dispatchers.IO) {
            // Stop playback if deleting active note
            if (_playingNoteId.value == note.id) {
                withContext(Dispatchers.Main) {
                    stopAudio()
                }
            }

            // Remove physical file
            val file = File(note.filePath)
            if (file.exists()) {
                file.delete()
            }

            database.voiceNoteDao().deleteVoiceNote(note)
        }
    }

    // Helper to convert File to Base64
    private fun File.toBase64(): String {
        val fileBytes = this.readBytes()
        return Base64.encodeToString(fileBytes, Base64.NO_WRAP)
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        recordingJob?.cancel()
        playbackJob?.cancel()
    }
}
