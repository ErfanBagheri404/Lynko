package com.lynko.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

object ScreenPermission {
    var resultCode: Int = Activity.RESULT_CANCELED
        private set
    var resultData: Intent? = null
        private set

    val isGranted get() = resultCode == Activity.RESULT_OK && resultData != null

    fun request(activity: Activity) {
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(mpm.createScreenCaptureIntent(), 9001)
    }

    fun onResult(requestCode: Int, code: Int, data: Intent?) {
        if (requestCode == 9001 && code == Activity.RESULT_OK && data != null) {
            resultCode = code
            resultData = data
        }
    }
}
