package com.example.acousticlogger

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal class ContextCompatExecutor(context: Context) : Executor {
    private val executor = Executors.newSingleThreadExecutor()

    override fun execute(command: Runnable) {
        executor.execute(command)
    }
}
