package com.example.indoornavapp.util

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import java.io.IOException

object AssetUtils {
    fun getDrawableFromAsset(context: Context, filename: String): Drawable? {
        return try {
            val stream = context.assets.open(filename)
            Drawable.createFromStream(stream, null)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
}
