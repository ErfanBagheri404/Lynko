package com.lynko.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

object BatteryLevel {
    fun read(ctx: Context): Int {
        val bfm = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return bfm?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    }

    fun isCharging(ctx: Context): Boolean {
        val bfm = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = bfm?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        return status == BatteryManager.BATTERY_STATUS_CHARGING
    }
}
