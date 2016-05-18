package org.jetbrains.ktor.content

import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.nio.*
import org.jetbrains.ktor.pipeline.*
import org.jetbrains.ktor.util.*
import java.io.*
import java.time.*
import java.util.concurrent.*

interface Resource : HasVersions {
    val contentType: ContentType
    override val versions: List<Version>
    val expires: LocalDateTime?
    val cacheControl: CacheControl?
    @Deprecated("Shouldn't it be somewhere else instead?")
    val attributes: Attributes
    val contentLength: Long?

    override val headers: ValuesMap
        get() = ValuesMap.build(true) {
            append(HttpHeaders.ContentType, contentType.toString())
            expires?.let { expires ->
                append(HttpHeaders.Expires, expires.toHttpDateString())
            }
            cacheControl?.let { cacheControl ->
                append(HttpHeaders.CacheControl, cacheControl.toString())
            }
            contentLength?.let { contentLength ->
                append(HttpHeaders.ContentLength, contentLength.toString())
            }
            appendAll(super.headers)
        }
}

sealed class FinalContent {
    open val status: HttpStatusCode? = null
    abstract val headers: ValuesMap
    abstract fun startContent(call: ApplicationCall, context: PipelineContext<Any>)

    abstract class NoContent : FinalContent() {
        override fun startContent(call: ApplicationCall, context: PipelineContext<Any>) {
            context.finishAll()
        }
    }

    abstract class ChannelContent : FinalContent() {
        abstract fun channel(): AsyncReadChannel

        override fun startContent(call: ApplicationCall, context: PipelineContext<Any>) {
            context.sendAsyncChannel(call, channel())
        }
    }

    abstract class StreamContentProvider : FinalContent() {
        abstract fun stream(): InputStream

        override fun startContent(call: ApplicationCall, context: PipelineContext<Any>) {
            context.sendStream(call, stream())
        }
    }

    abstract class StreamConsumer : FinalContent() {
        abstract fun stream(out : OutputStream): Unit

        override fun startContent(call: ApplicationCall, context: PipelineContext<Any>) {
            throw UnsupportedOperationException("It should never pass here: should be resend in BaseApplicationCall instead")
        }
    }

}

private fun PipelineContext<*>.createMachineCompletableFuture() = CompletableFuture<Long>().apply {
    whenComplete { total, throwable ->
        runBlockWithResult {
            handleThrowable(throwable)
        }
    }
}

private fun PipelineContext<*>.handleThrowable(throwable: Throwable?) {
    if (throwable == null || throwable is PipelineContinue || throwable.cause is PipelineContinue) {
        finishAll()
    } else if (throwable !is PipelineControlFlow && throwable.cause !is PipelineControlFlow) {
        fail(throwable)
    }
}

private fun PipelineContext<Any>.sendAsyncChannel(call: ApplicationCall, channel: AsyncReadChannel): Nothing {
    val future = createMachineCompletableFuture()

    closeAtEnd(channel, call) // TODO closeAtEnd(call) should be done globally at call start
    channel.copyToAsyncThenComplete(call.response.channel(), future)
    pause()
}

private fun PipelineContext<Any>.sendStream(call: ApplicationCall, stream: InputStream): Nothing {
    val future = createMachineCompletableFuture()

    closeAtEnd(stream, call) // TODO closeAtEnd(call) should be done globally at call start
    stream.asAsyncChannel().copyToAsyncThenComplete(call.response.channel(), future)
    pause()
}


internal fun PipelineContext<*>.closeAtEnd(vararg closeables: Closeable) {
    fun end() {
        for (closeable in closeables) {
            closeable.closeQuietly()
        }
    }

    onSuccess {
        end()
    }
    onFail {
        end()
    }
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (ignore: Throwable) {
    }
}
