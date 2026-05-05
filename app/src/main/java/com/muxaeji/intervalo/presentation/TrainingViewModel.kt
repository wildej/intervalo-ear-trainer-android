package com.muxaeji.intervalo.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muxaeji.intervalo.data.audio.IntervalAudioPlayer
import com.muxaeji.intervalo.data.audio.IntervalAudioPlayerProvider
import com.muxaeji.intervalo.domain.CheckAnswerUseCase
import com.muxaeji.intervalo.domain.GenerateQuestionUseCase
import com.muxaeji.intervalo.domain.Interval
import com.muxaeji.intervalo.domain.Note
import com.muxaeji.intervalo.domain.Question
import com.muxaeji.intervalo.domain.SessionStats
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class TrainingUiState(
    val selectedIntervals: Set<Interval> = setOf(Interval.MINOR_SECOND, Interval.MAJOR_SECOND),
    val useFixedBaseNote: Boolean = false,
    val baseMidiNote: Int = 60,
    val currentQuestion: Question? = null,
    val selectedAnswer: Interval? = null,
    val isAnswerChecked: Boolean = false,
    val isAnswerCorrect: Boolean = false,
    val isPlaying: Boolean = false,
    val stats: SessionStats = SessionStats(),
    val gameItems: List<Interval> = emptyList(),
    val gameOptions: List<Interval> = emptyList(),
    val gameSelectedLeft: Interval? = null,
    val gameCompleted: Set<Interval> = emptySet(),
    val gameErrorMessage: String? = null,
    val gameLives: Int = GAME_INITIAL_LIVES,
    val gameRoundsCompleted: Int = 0,
    val gameBestRounds: Int = 0,
    val isGameOver: Boolean = false
)

private const val GAME_INITIAL_LIVES = 5
private const val GAME_ROUND_SIZE = 6
private const val GAME_PREFS_NAME = "game_mode_prefs"
private const val KEY_BEST_ROUNDS = "best_rounds"

class TrainingViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val generateQuestionUseCase = GenerateQuestionUseCase()
    private val checkAnswerUseCase = CheckAnswerUseCase()
    private val audioPlayer: IntervalAudioPlayer =
        IntervalAudioPlayerProvider.create(application)
    private val random: Random = Random.Default
    private val prefs = application.getSharedPreferences(GAME_PREFS_NAME, Context.MODE_PRIVATE)
    private val bestRoundsStored: Int = prefs.getInt(KEY_BEST_ROUNDS, 0)

    private val _uiState = MutableStateFlow(TrainingUiState(gameBestRounds = bestRoundsStored))
    val uiState: StateFlow<TrainingUiState> = _uiState.asStateFlow()

    fun toggleInterval(interval: Interval) {
        _uiState.update { state ->
            val updated = state.selectedIntervals.toMutableSet().apply {
                if (!add(interval)) remove(interval)
            }
            state.copy(selectedIntervals = updated)
        }
    }

    fun toggleFixedBaseNote() {
        _uiState.update { it.copy(useFixedBaseNote = !it.useFixedBaseNote) }
    }

    private fun generateQuestionFromSettings(): Question {
        val state = _uiState.value
        return generateQuestionUseCase.generate(
            selectedIntervals = state.selectedIntervals.toList(),
            fixedRootMidi = if (state.useFixedBaseNote) state.baseMidiNote else null
        )
    }

    fun startSession() {
        val question = generateQuestionFromSettings()
        _uiState.update {
            it.copy(
                currentQuestion = question,
                selectedAnswer = null,
                isAnswerChecked = false,
                isAnswerCorrect = false,
                stats = SessionStats()
            )
        }
        playCurrentQuestion()
    }

    fun selectAnswer(interval: Interval) {
        val state = _uiState.value
        if (state.isAnswerChecked) return
        val question = state.currentQuestion ?: return
        val correct = checkAnswerUseCase.isCorrect(question, interval)
        _uiState.update {
            it.copy(
                selectedAnswer = interval,
                isAnswerChecked = true,
                isAnswerCorrect = correct,
                stats = it.stats.copy(
                    total = it.stats.total + 1,
                    correct = it.stats.correct + if (correct) 1 else 0
                )
            )
        }
    }

    fun nextQuestion() {
        val question = generateQuestionFromSettings()
        _uiState.update {
            it.copy(
                currentQuestion = question,
                selectedAnswer = null,
                isAnswerChecked = false,
                isAnswerCorrect = false
            )
        }
        playCurrentQuestion()
    }

    fun playCurrentQuestion() {
        val question = _uiState.value.currentQuestion ?: return
        if (_uiState.value.isPlaying) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPlaying = true) }
            runCatching {
                audioPlayer.playInterval(question.root, question.top)
            }
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    fun playIntervalPreview(interval: Interval) {
        if (_uiState.value.isPlaying) return
        val root = Note(60)
        val top = Note(root.midi + interval.semitones)
        viewModelScope.launch {
            _uiState.update { it.copy(isPlaying = true) }
            runCatching {
                audioPlayer.playInterval(root, top)
            }
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    fun startGameMode() {
        val items = Interval.entries.shuffled(random).take(GAME_ROUND_SIZE)
        _uiState.update {
            it.copy(
                gameItems = items,
                gameOptions = items.shuffled(random),
                gameSelectedLeft = null,
                gameCompleted = emptySet(),
                gameErrorMessage = null,
                gameLives = GAME_INITIAL_LIVES,
                gameRoundsCompleted = 0,
                isGameOver = false
            )
        }
    }

    fun startNextGameRound() {
        val state = _uiState.value
        if (state.isGameOver || state.gameLives <= 0 || state.gameCompleted.size != state.gameItems.size) return
        val items = Interval.entries.shuffled(random).take(GAME_ROUND_SIZE)
        _uiState.update {
            it.copy(
                gameItems = items,
                gameOptions = items.shuffled(random),
                gameSelectedLeft = null,
                gameCompleted = emptySet(),
                gameErrorMessage = null,
                gameRoundsCompleted = it.gameRoundsCompleted + 1
            )
        }
    }

    fun playGamePrompt(index: Int) {
        val state = _uiState.value
        if (state.isPlaying || state.isGameOver || index !in state.gameItems.indices) return
        val interval = state.gameItems[index]
        if (state.gameCompleted.contains(interval)) return
        val root = Note(60)
        val top = Note(root.midi + interval.semitones)
        viewModelScope.launch {
            _uiState.update { it.copy(isPlaying = true, gameSelectedLeft = interval, gameErrorMessage = null) }
            runCatching {
                audioPlayer.playInterval(root, top)
            }
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching {
            runBlocking {
                withContext(NonCancellable) {
                    audioPlayer.close()
                }
            }
        }
    }

    fun selectGameAnswer(selected: Interval) {
        val state = _uiState.value
        if (state.isGameOver) return
        if (state.gameCompleted.contains(selected)) return
        val expected = state.gameSelectedLeft ?: return
        if (selected == expected) {
            val completed = state.gameCompleted + expected
            _uiState.update {
                it.copy(
                    gameCompleted = completed,
                    gameSelectedLeft = null,
                    gameErrorMessage = null
                )
            }
        } else {
            val nextLives = (state.gameLives - 1).coerceAtLeast(0)
            val isGameOver = nextLives == 0
            val newBest = if (isGameOver) maxOf(state.gameBestRounds, state.gameRoundsCompleted) else state.gameBestRounds
            if (isGameOver && newBest > state.gameBestRounds) {
                prefs.edit().putInt(KEY_BEST_ROUNDS, newBest).apply()
            }
            _uiState.update {
                it.copy(
                    gameLives = nextLives,
                    isGameOver = isGameOver,
                    gameBestRounds = newBest,
                    gameErrorMessage = if (isGameOver) {
                        "Игра окончена"
                    } else {
                        "Неверно, попробуйте ещё раз"
                    }
                )
            }
        }
    }
}
