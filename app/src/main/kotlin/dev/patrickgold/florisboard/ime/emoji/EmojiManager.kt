package dev.patrickgold.florisboard.ime.emoji

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.emoji2.text.EmojiCompat
import dev.patrickgold.florisboard.app.florisPreferenceModel
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.media.emoji.Emoji
import dev.patrickgold.florisboard.ime.media.emoji.FlorisEmojiCompat
import dev.patrickgold.florisboard.ime.media.emoji.parseRawEmojiSpecsFile
import dev.patrickgold.florisboard.ime.nlp.EmojiSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.lib.kotlin.collectIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val EMOJI_SUGGESTION_INDICATOR = ':'
private const val EMOJI_SUGGESTION_DEBOUNCE_MS = 400L
private const val EMOJI_SUGGESTION_QUERY_MIN_LENGTH = 2
private const val EMOJI_SUGGESTION_MAX_COUNT = 5

/**
 * Manages emoji suggestions within the editor.
 *
 * This class handles the following tasks:
 * - Initializing and maintaining a list of supported emojis.
 * - Monitoring user input in the editor.
 * - Generating emoji suggestions based on user input and preferences.
 * - Providing a flow of active emoji suggestions for UI consumption.
 *
 * @param context The application context.
 */
@OptIn(FlowPreview::class)
class EmojiManager(private val context: Context) {
    private val prefs by florisPreferenceModel()
    private val editorInstance by context.editorInstance()
    private val supportedEmojiList: List<Emoji> by lazy { initSupportedEmojiList() }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _activeCandidatesFlow = MutableStateFlow(listOf<SuggestionCandidate>())

    /**
     * A flow of active emoji suggestion candidates.
     */
    val activeCandidatesFlow = _activeCandidatesFlow.asStateFlow()

    init {
        // Observe changes in editor content, handling them with debounce and distinctUntilChanged
        editorInstance.activeContentFlow.debounce(EMOJI_SUGGESTION_DEBOUNCE_MS)
            .distinctUntilChanged { old, new -> old.composingText == new.composingText }
            .map {
                // Check if suggestions are enabled in preferences
                if (!prefs.suggestion.enabled.get()) {
                    return@map emptyList()
                }

                // Extract query for suggestion from composing text
                val query = editorInstance.activeContent.composingText.let { composingText ->
                    if (composingText.length <= EMOJI_SUGGESTION_QUERY_MIN_LENGTH) {
                        null
                    } else {
                        composingText.substring(composingText.lastIndexOf(EMOJI_SUGGESTION_INDICATOR) + 1)
                    }
                } ?: return@map emptyList()

                // 1. Filter and generate emoji suggestions based on user input and preferences.
                // 2. Limit suggestions, convert to candidates, and update active flow.
                supportedEmojiList.asSequence()
                    .filter { emoji -> emoji.name.contains(query) && emoji.keywords.any { it.contains(query) } }
                    .take(EMOJI_SUGGESTION_MAX_COUNT).map(::EmojiSuggestionCandidate).toList()
            }.collectIn(scope) {
                _activeCandidatesFlow.value = it
            }
    }


    /**
     * Initializes the list of supported emojis.
     */
    private fun initSupportedEmojiList(): List<Emoji> {
        val activeEditorInfo = editorInstance.activeInfo
        val emojiCompatInstance = FlorisEmojiCompat.getAsFlow(activeEditorInfo.emojiCompatReplaceAll).value
        val systemFontPaint = Paint().apply { typeface = Typeface.DEFAULT }
        val emojiPreferredSkinTone = prefs.media.emojiPreferredSkinTone.get()
        val isEmojiSupported = { emoji: Emoji ->
            val emojiMatch =
                emojiCompatInstance?.getEmojiMatch(emoji.value, activeEditorInfo.emojiCompatMetadataVersion)
            emojiMatch == EmojiCompat.EMOJI_SUPPORTED || systemFontPaint.hasGlyph(emoji.value)
        }
        return parseRawEmojiSpecsFile(context, "ime/media/emoji/root.txt").values.flatten()
                .map { it.base(emojiPreferredSkinTone) }.filter { isEmojiSupported(it) }
    }
}
