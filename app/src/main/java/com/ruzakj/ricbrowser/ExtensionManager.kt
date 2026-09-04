package com.ruzakj.ricbrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

class ExtensionManager(private val context: Context) {
    data class Extension(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val matches: List<String>,
        val excludes: List<String>,
        val runAt: String,
        val script: String,
        val css: String,
        val type: String,
        var enabled: Boolean = true
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Extension> = loadAll()

    fun setEnabled(id: String, enabled: Boolean) {
        val all = loadAll().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index >= 0) {
            all[index] = all[index].copy(enabled = enabled)
            saveAll(all)
        }
    }

    fun remove(id: String) {
        saveAll(loadAll().filterNot { it.id == id })
    }

    fun import(uri: Uri): Result<Extension> = runCatching {
        val name = queryName(uri).lowercase(Locale.ROOT)
        val extension = if (name.endsWith(".ricx") || name.endsWith(".zip")) {
            importRicx(uri)
        } else {
            importUserScript(uri)
        }
        val all = loadAll().toMutableList()
        all.removeAll { it.id == extension.id || (it.name == extension.name && it.type == extension.type) }
        all.add(extension)
        saveAll(all)
        extension
    }

    fun javascriptFor(url: String, phase: String): List<String> {
        return loadAll().asSequence()
            .filter { it.enabled && matches(it, url) }
            .filter { phaseMatches(it.runAt, phase) }
            .map { buildInjection(it) }
            .toList()
    }

    private fun phaseMatches(runAt: String, phase: String): Boolean {
        val normalized = runAt.lowercase(Locale.ROOT).replace('_', '-')
        return when (phase) {
            "start" -> normalized == "document-start"
            "end" -> normalized != "document-start"
            else -> false
        }
    }

    private fun matches(extension: Extension, url: String): Boolean {
        if (extension.excludes.any { globMatch(it, url) }) return false
        return extension.matches.any { globMatch(it, url) }
    }

    private fun globMatch(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p.isBlank()) return false
        if (p == "<all_urls>" || p == "*://*/*") return url.startsWith("http://") || url.startsWith("https://")
        val regex = buildString {
            append('^')
            p.forEach { c ->
                when (c) {
                    '*' -> append(".*")
                    '.', '?', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> append('\\').append(c)
                    else -> append(c)
                }
            }
            append('$')
        }
        return runCatching { Regex(regex, RegexOption.IGNORE_CASE).matches(url) }.getOrDefault(false)
    }

    private fun importUserScript(uri: Uri): Extension {
        val text = readText(uri, MAX_EXTENSION_BYTES)
        require(text.isNotBlank()) { "Empty userscript" }
        val meta = parseUserScriptMetadata(text)
        val displayName = meta["name"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: queryName(uri).removeSuffix(".user.js").removeSuffix(".js").ifBlank { "Imported UserScript" }
        val matches = (meta["match"].orEmpty() + meta["include"].orEmpty()).filter { it.isNotBlank() }.ifEmpty { listOf("*://*/*") }
        val excludes = meta["exclude-match"].orEmpty() + meta["exclude"].orEmpty()
        return Extension(
            id = stableId("userscript:$displayName"),
            name = displayName.take(80),
            version = meta["version"]?.firstOrNull()?.take(24) ?: "1.0",
            description = meta["description"]?.firstOrNull()?.take(180).orEmpty(),
            matches = matches.take(MAX_PATTERNS),
            excludes = excludes.take(MAX_PATTERNS),
            runAt = meta["run-at"]?.firstOrNull() ?: "document-end",
            script = text,
            css = "",
            type = "UserScript"
        )
    }

    private fun importRicx(uri: Uri): Extension {
        var manifestText: String? = null
        var script = ""
        var css = ""
        var total = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val safeName = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                    if (safeName !in setOf("manifest.json", "content.js", "style.css")) continue
                    val bytes = readEntry(zip, MAX_EXTENSION_BYTES - total)
                    total += bytes.size
                    require(total <= MAX_EXTENSION_BYTES) { "Extension is too large" }
                    val text = bytes.toString(Charsets.UTF_8)
                    when (safeName) {
                        "manifest.json" -> manifestText = text
                        "content.js" -> script = text
                        "style.css" -> css = text
                    }
                }
            }
        } ?: error("Unable to read extension")

        val manifest = JSONObject(manifestText ?: error("manifest.json is required"))
        val displayName = manifest.optString("name").trim().ifBlank { error("Extension name is required") }
        val matches = jsonStringList(manifest.optJSONArray("matches")).ifEmpty { listOf("*://*/*") }
        val excludes = jsonStringList(manifest.optJSONArray("exclude_matches"))
        require(script.isNotBlank() || css.isNotBlank()) { "content.js or style.css is required" }
        return Extension(
            id = manifest.optString("id").trim().takeIf { it.matches(Regex("[A-Za-z0-9._-]{3,80}")) }
                ?: stableId("ricx:$displayName"),
            name = displayName.take(80),
            version = manifest.optString("version", "1.0").take(24),
            description = manifest.optString("description").take(180),
            matches = matches.take(MAX_PATTERNS),
            excludes = excludes.take(MAX_PATTERNS),
            runAt = manifest.optString("run_at", "document-end"),
            script = script,
            css = css,
            type = "Ric Extension"
        )
    }

    private fun buildInjection(ext: Extension): String {
        val safeId = JSONObject.quote(ext.id)
        val safeName = JSONObject.quote(ext.name)
        val safeVersion = JSONObject.quote(ext.version)
        val css = JSONObject.quote(ext.css)
        return """
(() => {
  try {
    const __ricId = $safeId;
    const __ricName = $safeName;
    const __ricVersion = $safeVersion;
    const GM_info = {script:{name:__ricName,version:__ricVersion},platform:{browserName:'Ric Browser'}};
    const unsafeWindow = window;
    const __ricKey = k => '__ric_ext_' + __ricId + '_' + k;
    const GM_getValue = (k,d=null) => { try { const v=localStorage.getItem(__ricKey(k)); return v===null?d:JSON.parse(v); } catch(_) { return d; } };
    const GM_setValue = (k,v) => { try { localStorage.setItem(__ricKey(k),JSON.stringify(v)); } catch(_) {} };
    const GM_deleteValue = k => { try { localStorage.removeItem(__ricKey(k)); } catch(_) {} };
    const GM_addStyle = css => { const s=document.createElement('style'); s.textContent=css; (document.head||document.documentElement).appendChild(s); return s; };
    const __ricCss = $css;
    if (__ricCss && !document.querySelector('style[data-ric-extension="'+__ricId+'"]')) {
      const s=document.createElement('style'); s.dataset.ricExtension=__ricId; s.textContent=__ricCss; (document.head||document.documentElement).appendChild(s);
    }
    ${ext.script}
  } catch (e) { console.warn('Ric extension error', e); }
})();
""".trimIndent()
    }

    private fun parseUserScriptMetadata(text: String): Map<String, List<String>> {
        val start = text.indexOf("// ==UserScript==")
        val end = text.indexOf("// ==/UserScript==")
        if (start < 0 || end <= start) return emptyMap()
        val map = linkedMapOf<String, MutableList<String>>()
        text.substring(start, end).lineSequence().forEach { line ->
            val match = Regex("^\\s*//\\s*@([\\w-]+)\\s+(.+?)\\s*$").find(line) ?: return@forEach
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            map.getOrPut(key) { mutableListOf() }.add(match.groupValues[2].trim())
        }
        return map
    }

    private fun jsonStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun queryName(uri: Uri): String {
        var result = uri.lastPathSegment ?: "extension.user.js"
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) result = cursor.getString(0) ?: result
        }
        return result
    }

    private fun readText(uri: Uri, maxBytes: Int): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "Extension is too large" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: error("Unable to read file")
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readEntry(zip: ZipInputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "Extension is too large" }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Extension is too large" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun stableId(seed: String): String = UUID.nameUUIDFromBytes(seed.lowercase(Locale.ROOT).toByteArray()).toString()

    private fun loadAll(): List<Extension> {
        val raw = prefs.getString(KEY_EXTENSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        Extension(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            version = o.optString("version", "1.0"),
                            description = o.optString("description"),
                            matches = jsonStringList(o.optJSONArray("matches")),
                            excludes = jsonStringList(o.optJSONArray("excludes")),
                            runAt = o.optString("runAt", "document-end"),
                            script = o.optString("script"),
                            css = o.optString("css"),
                            type = o.optString("type", "UserScript"),
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(items: List<Extension>) {
        val array = JSONArray()
        items.take(MAX_EXTENSIONS).forEach { e ->
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("version", e.version)
                    .put("description", e.description)
                    .put("matches", JSONArray(e.matches))
                    .put("excludes", JSONArray(e.excludes))
                    .put("runAt", e.runAt)
                    .put("script", e.script)
                    .put("css", e.css)
                    .put("type", e.type)
                    .put("enabled", e.enabled)
            )
        }
        prefs.edit().putString(KEY_EXTENSIONS, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "ric_extensions"
        private const val KEY_EXTENSIONS = "extensions_json"
        private const val MAX_EXTENSION_BYTES = 1024 * 1024
        private const val MAX_EXTENSIONS = 40
        private const val MAX_PATTERNS = 80
    }
}
