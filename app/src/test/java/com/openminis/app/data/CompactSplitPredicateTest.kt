package com.openminis.app.data

import com.openminis.app.data.model.LLMError
import com.openminis.app.ui.chat.ChatViewModel.Companion.shouldSplitOnError
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class CompactSplitPredicateTest {


    @Test
    fun `rate limiting does not split`() {
        assertFalse(shouldSplitOnError(LLMError.RateLimited()))
    }

    @Test
    fun `transient server errors do not split`() {
        assertFalse(shouldSplitOnError(LLMError.TransientError("502 Bad Gateway")))
    }

    @Test
    fun `auth failures do not split`() {
        assertFalse(shouldSplitOnError(LLMError.InvalidApiKey("bad key")))
    }

    @Test
    fun `cancellation does not split`() {
        assertFalse(shouldSplitOnError(CancellationException("user stopped")))
        assertFalse(shouldSplitOnError(LLMError.Cancelled()))
    }

    @Test
    fun `network failures do not split`() {
        assertFalse(shouldSplitOnError(LLMError.NetworkError(IOException("offline"))))
        assertFalse(shouldSplitOnError(IOException("connection reset")))
        assertFalse(shouldSplitOnError(SocketTimeoutException("read timed out")))
    }


    @Test
    fun `over-length provider refusals still split`() {
        assertTrue(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[context_length_exceeded] Your input exceeds the context window of this model"
                )
            )
        )
        assertTrue(shouldSplitOnError(LLMError.ProviderError("400 request too large")))
    }

    @Test
    fun `unclassified errors still split`() {
        assertTrue(shouldSplitOnError(LLMError.Unknown(RuntimeException("???"))))
        assertTrue(shouldSplitOnError(LLMError.DecodingError(RuntimeException("bad json"))))
        assertTrue(shouldSplitOnError(IllegalStateException("something odd")))
    }


    @Test
    fun `an unsupported-parameter 400 does not split`() {
        assertFalse(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[400] reasoning.effort: 'none' is not supported. " +
                        "Supported values are: 'low', 'medium', 'high', 'xhigh' and 'max'.",
                    httpStatus = 400,
                ),
            ),
        )
    }

    @Test
    fun `other parameter rejections do not split`() {
        for (m in listOf(
            "[400] invalid_request_error: unknown field 'reasoning'",
            "[422] extra_forbidden body.reasoning",
            "Model grok-build-0.1 does not support parameter reasoningEffort",
            "[400] Unsupported parameter: 'temperature'",
        )) {
            assertFalse("must not split: $m", shouldSplitOnError(LLMError.ProviderError(m, httpStatus = 400)))
        }
    }

    @Test
    fun `an over-length 400 still splits`() {
        assertTrue(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[400] context_length_exceeded: input too long",
                    httpStatus = 400,
                ),
            ),
        )
        assertTrue(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[400] This model's maximum context length is 128000 tokens",
                    httpStatus = 400,
                ),
            ),
        )
    }

    @Test
    fun `a message mixing both signals errs towards splitting`() {
        assertTrue(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[400] invalid_request_error: input exceeds the context window",
                    httpStatus = 400,
                ),
            ),
        )
    }

    @Test
    fun `5xx and status-less errors are unaffected`() {
        assertTrue(shouldSplitOnError(LLMError.ProviderError("upstream boom", httpStatus = 500)))
        assertTrue(shouldSplitOnError(LLMError.ProviderError("no status here")))
    }

    @Test
    fun `the amplification path is closed for the reported failure modes`() {
        val previouslyAmplified = listOf<Throwable>(
            LLMError.RateLimited(),
            LLMError.TransientError("503"),
            LLMError.InvalidApiKey(),
        )
        for (e in previouslyAmplified) {
            assertFalse("${e.javaClass.simpleName} must not split", shouldSplitOnError(e))
        }
    }
}
