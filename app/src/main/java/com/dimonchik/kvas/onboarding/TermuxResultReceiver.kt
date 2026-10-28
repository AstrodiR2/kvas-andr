package com.dimonchik.kvas.onboarding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra(TermuxBridge.RESULT_BUNDLE)
        val exit = bundle?.getInt("exitCode", -1) ?: -1
        val stdout = bundle?.getString("stdout").orEmpty()
        val stderr = bundle?.getString("stderr").orEmpty()
        val requestId = intent.getStringExtra("requestId")
        if (requestId != null) {
            context.getSharedPreferences(TermuxBridge.RPC_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("$requestId.finished", true)
                .putInt("$requestId.exit", exit)
                .putString("$requestId.stdout", stdout.take(2_000_000))
                .putString("$requestId.stderr", stderr.take(20_000))
                .apply()
            return
        }
        context.getSharedPreferences("kvas_install", Context.MODE_PRIVATE).edit()
            .putBoolean("finished", true)
            .putInt("exit", exit)
            .putString("output", (stdout + "\n" + stderr).takeLast(5000))
            .apply()
    }
}
