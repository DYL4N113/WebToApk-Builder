package com.dyl4n.webtoapk.engine

import android.content.Context
import org.json.JSONObject

class TemplateLoader {
    fun generateManifest(
        context: Context,
        appName: String,
        packageName: String,
        hasIcon: Boolean,
    ): String {
        val template = context.assets.open("templates/AndroidManifest.xml")
            .bufferedReader()
            .use { it.readText() }

        return template
            .replace("{{APP_NAME}}", appName.escapeXmlAttribute())
            .replace("{{PACKAGE_NAME}}", packageName.escapeXmlAttribute())
            .replace(
                "{{APP_ICON}}",
                if (hasIcon) "@drawable/icon" else "@android:drawable/sym_def_app_icon",
            )
    }

    fun generateIndexHtml(context: Context, url: String): String {
        val template = context.assets.open("templates/index.html")
            .bufferedReader()
            .use { it.readText() }
        val escapedUrl = JSONObject.quote(url).replace("<", "\\u003c")
        return template.replace("{{URL}}", escapedUrl)
    }

    private fun String.escapeXmlAttribute(): String = buildString(length) {
        for (character in this@escapeXmlAttribute) {
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }
}
