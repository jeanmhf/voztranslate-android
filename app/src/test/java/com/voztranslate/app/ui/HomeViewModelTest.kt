package com.voztranslate.app.ui

import com.voztranslate.app.domain.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeViewModelTest {
    @Test
    fun startsWithEnglishToSpanish() {
        val viewModel = HomeViewModel()

        assertEquals(
            HomeUiState(
                sourceLanguage = Language.ENGLISH,
                targetLanguage = Language.SPANISH,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun selectingSourcePreservesTargetAndSelectingCurrentTargetSwapsThePair() {
        val viewModel = HomeViewModel()

        viewModel.selectSourceLanguage(Language.MANDARIN)

        assertEquals(Language.MANDARIN, viewModel.uiState.value.sourceLanguage)
        assertEquals(Language.SPANISH, viewModel.uiState.value.targetLanguage)
        assertEquals(Language.MANDARIN, viewModel.modelPreparation.value.language)
        assertDistinctLanguages(viewModel)

        // Selecting the current target as source exchanges the pair.
        viewModel.selectSourceLanguage(Language.SPANISH)

        assertEquals(Language.SPANISH, viewModel.uiState.value.sourceLanguage)
        assertEquals(Language.MANDARIN, viewModel.uiState.value.targetLanguage)
        assertEquals(Language.SPANISH, viewModel.modelPreparation.value.language)
        assertDistinctLanguages(viewModel)
    }

    @Test
    fun selectingTargetPreservesSourceAndSelectingCurrentSourceSwapsThePair() {
        val viewModel = HomeViewModel()

        viewModel.selectTargetLanguage(Language.MANDARIN)

        assertEquals(Language.ENGLISH, viewModel.uiState.value.sourceLanguage)
        assertEquals(Language.MANDARIN, viewModel.uiState.value.targetLanguage)
        assertDistinctLanguages(viewModel)

        // Selecting the current source as target exchanges the pair.
        viewModel.selectTargetLanguage(Language.ENGLISH)

        assertEquals(Language.MANDARIN, viewModel.uiState.value.sourceLanguage)
        assertEquals(Language.ENGLISH, viewModel.uiState.value.targetLanguage)
        assertEquals(Language.MANDARIN, viewModel.modelPreparation.value.language)
        assertDistinctLanguages(viewModel)
    }

    @Test
    fun swappingLanguagesExchangesThePairAndIsReversible() {
        val viewModel = HomeViewModel()
        val initial = viewModel.uiState.value

        viewModel.swapLanguages()

        assertEquals(Language.SPANISH, viewModel.uiState.value.sourceLanguage)
        assertEquals(Language.ENGLISH, viewModel.uiState.value.targetLanguage)
        assertDistinctLanguages(viewModel)

        viewModel.swapLanguages()

        assertEquals(initial, viewModel.uiState.value)
    }

    @Test
    fun recordingStaysDisabledUntilTheSelectedAsrAndTranslationModelsAreReady() {
        val viewModel = HomeViewModel()

        assertFalse(viewModel.canStartRecording())
        assertEquals(ModelPreparationPhase.IDLE, viewModel.modelPreparation.value.phase)
        assertEquals(ModelPreparationPhase.IDLE, viewModel.translationModelPreparation.value.phase)

        viewModel.selectSourceLanguage(Language.MANDARIN)
        viewModel.selectTargetLanguage(Language.ENGLISH)

        assertEquals(Language.MANDARIN, viewModel.modelPreparation.value.language)
        assertDistinctLanguages(viewModel)
        assertEquals(ModelPreparationPhase.IDLE, viewModel.modelPreparation.value.phase)
        assertEquals(ModelPreparationPhase.IDLE, viewModel.translationModelPreparation.value.phase)
        assertFalse(viewModel.canStartRecording())

        viewModel.swapLanguages()

        assertEquals(Language.ENGLISH, viewModel.uiState.value.sourceLanguage)
        assertEquals(Language.MANDARIN, viewModel.uiState.value.targetLanguage)
        assertEquals(Language.ENGLISH, viewModel.modelPreparation.value.language)
        assertDistinctLanguages(viewModel)
        assertEquals(ModelPreparationPhase.IDLE, viewModel.modelPreparation.value.phase)
        assertFalse(viewModel.canStartRecording())
    }

    private fun assertDistinctLanguages(viewModel: HomeViewModel) {
        assertNotEquals(
            viewModel.uiState.value.sourceLanguage,
            viewModel.uiState.value.targetLanguage,
        )
    }
}
