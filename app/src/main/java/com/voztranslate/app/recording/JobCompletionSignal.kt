package com.voztranslate.app.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** Completes a deferred even when a coroutine is cancelled before its body starts. */
internal fun Job.completeSignalOnCompletion(signal: CompletableDeferred<Throwable?>) {
    invokeOnCompletion { cause -> signal.complete(cause) }
}
