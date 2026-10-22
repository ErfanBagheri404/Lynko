package com.lynko.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/** Samsung Flow parity: the phone's launcher apps, for the desktop's
 *  Favorites picker, and launching one of them.
 *
 *  Flow lets you pin up to 8 apps inside the mirror window and open them
 *  with one click (IN/PL support pages). The desktop needs the app LIST to
 *  render that picker, and the phone must actually start the activity —
 *  the desktop cannot do it, it has no launcher.
 */
object AppCatalog {

    /** Launchable apps, sorted by label. Empty when package visibility is
     *  blocked (Android 11+ needs the <queries> entry in the manifest —
     *  without it queryIntentActivities returns only our own package). */
    fun list(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = try {
            @Suppress("DEPRECATION", "QueryPermissionsNeeded")
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        return resolved
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                val label = try {
                    ri.loadLabel(pm).toString()
                } catch (_: Exception) {
                    pkg
                }
                pkg to label
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    /** Start an app by package name. Returns false when the package has no
     *  launch intent (uninstalled between the list and the click). */
    fun launch(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val pm = ctx.packageManager
        val intent: Intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        // A Service has no task of its own — without NEW_TASK the start
        // throws AndroidRuntimeException: Calling startActivity() from
        // outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** True when the package is still installed — a favorite clicked after
     *  the app was uninstalled must not crash the phone. */
    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
}
