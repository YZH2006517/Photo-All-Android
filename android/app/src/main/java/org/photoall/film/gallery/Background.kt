package org.photoall.film.gallery

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Work for a screen's own thread, dropped once the screen is gone: a media change or a
 * finished decode can still ask for more after onDestroy has shut the executor down
 * (on some phones the system's media scanner makes that common).
 */
fun ExecutorService.later(task: () -> Unit) {
    if (isShutdown) return
    try {
        execute(task)
    } catch (_: RejectedExecutionException) {
        // shut down between the check and the call: nothing to do
    }
}
