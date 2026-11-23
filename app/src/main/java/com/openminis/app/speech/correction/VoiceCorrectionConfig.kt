package com.openminis.app.speech.correction

object VoiceCorrectionConfig {

    const val MIN_CONFUSION_CONFIDENCE = 0.3

    const val MAX_CANDIDATES = 20

    const val RETRIEVAL_BUDGET_MS = 80L

    const val CORRECTION_BUDGET_MS = 15_000L

    const val MAX_CHAR_CHANGE_RATIO = 0.5

    const val VOCAB_MIN_FREQUENCY = 3

    const val VOCAB_MIN_DISTINCT_DAYS = 2

    const val VOCAB_MIN_SCORE = 2

    const val VOCAB_BACKGROUND_RANK_THRESHOLD = 3000

    const val CACHE_TTL_MS = 300_000L

    const val CACHE_LIMIT = 200


    const val PHONETIC_SIMILARITY_THRESHOLD = 0.6

    @Deprecated("Superseded by CorrectionAdmission.Config.MAX_EDIT_LOCALITY")
    const val MAX_LENGTH_RATIO = 2.0

    const val CONTEXT_SAMPLE_CHARS = 50
}

object CorrectionContextBudget {
    const val MESSAGE_CONTEXT_TOTAL = 5000

    const val RARE_DIGEST = 200
    const val MESSAGE_EXCERPTS = 4800
    const val PER_MESSAGE_EXCERPT = 600
    const val GROUNDING_EXCERPT = 500
    const val CONFUSION_BLOCK = 800
    const val VOCAB_BLOCK = 400

    const val MAX_MESSAGES_SCANNED = 12
    const val PER_MESSAGE_SCAN_CAP = 2000


    const val SCREEN_VIEWPORT = 2000

    const val SCREEN_LATEST_REPLY = 2000

    const val SCREEN_EXTENSION_SCREENS = 0.5
}
