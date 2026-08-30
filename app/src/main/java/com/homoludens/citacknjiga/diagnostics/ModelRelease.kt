package com.homoludens.citacknjiga.diagnostics

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.net.URI

/** Validates and dispatches the optional release page without performing a request. */
public object ModelReleaseAction {
    public fun validatedUrl(value: String): URI? = runCatching {
        val uri = URI(value.trim())
        if (!uri.isAbsolute || uri.isOpaque || uri.userInfo != null || uri.host.isNullOrBlank()) return null
        if (uri.scheme.lowercase() !in setOf("http", "https")) return null
        uri
    }.getOrNull()

    public fun canOpen(context: Context, value: String): Boolean {
        val uri = validatedUrl(value) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()))
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.packageName != context.packageName }
    }

    public fun open(context: Context, value: String): Boolean {
        val uri = validatedUrl(value) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()))
        if (!context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName != context.packageName }) return false
        context.startActivity(intent)
        return true
    }
}
