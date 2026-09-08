package com.ruzakj.ricbrowser

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import java.lang.reflect.Method
import java.util.LinkedHashMap
import java.util.WeakHashMap

object MediaDirectPatch {
    private val attached = WeakHashMap<Activity, Boolean>()

    fun attach(activity: Activity) {
        if (activity !is MainActivity || attached[activity] == true) return
        val button = findMediaButton(activity.window.decorView) ?: return
        attached[activity] = true
        button.setOnClickListener { openMedia(activity) }
    }

    private fun openMedia(activity: MainActivity) {
        val items = mediaItems(activity)
        when (items.size) {
            0 -> Unit
            1 -> playMx(activity, items.first())
            else -> {
                val labels = items.mapIndexed { index, item ->
                    val kind = runCatching { item.javaClass.getDeclaredField("kind").apply { isAccessible = true }.get(item).toString() }.getOrDefault("MEDIA")
                    "$kind • Media ${index + 1}"
                }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle("Play with MX Player (${items.size})")
                    .setItems(labels) { _, index -> playMx(activity, items[index]) }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun mediaItems(activity: MainActivity): List<Any> = runCatching {
        val field = MainActivity::class.java.getDeclaredField("mediaItems").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(activity) as LinkedHashMap<String, Any>
        synchronized(map) { map.values.toList() }
    }.getOrDefault(emptyList())

    private fun playMx(activity: MainActivity, item: Any) {
        runCatching {
            val method: Method = MainActivity::class.java.declaredMethods.first {
                it.name == "playMedia" && it.parameterTypes.size == 2
            }.apply { isAccessible = true }
            method.invoke(activity, item, true)
        }
    }

    private fun findMediaButton(root: View): Button? {
        if (root is Button && root.text?.toString()?.startsWith("↓") == true) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findMediaButton(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
