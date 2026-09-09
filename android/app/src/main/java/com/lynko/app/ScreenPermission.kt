package com.lynko.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

object ScreenPermission {
    var resultCode: Int = Activity.RESULT_CANCELED
        private set
    var resultData: Intent? = null
        internal set

    val isGranted get() = resultCode == Activity.RESULT_OK && resultData != null

    /** Launch the consent dialog. Works from an Activity or a Service
     * (from a Service we need NEW_TASK since there's no activity stack). */
    fun request(activity: Activity) {
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(mpm.createScreenCaptureIntent(), 9001)
    }

    fun requestFromService(context: Context) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val i = mpm.createScreenCaptureIntent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }

    fun onResult(requestCode: Int, code: Int, data: Intent?) {
        if (requestCode == 9001 && code == Activity.RESULT_OK && data != null) {
            resultCode = code
            resultData = data
        }
    }

    /** Clear a stale/expired consent token. */
    fun invalidate() {
        resultCode = Activity.RESULT_CANCELED
        resultData = null
    }
}
