from pathlib import Path

p = Path('app/src/main/java/com/ruzakj/ricbrowser/PowerTools.kt')
s = p.read_text(encoding='utf-8')
old = '0 -> runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast(a, "No compatible player") }'
new = '''0 -> runCatching {
                        val lower = url.lowercase(Locale.ROOT)
                        val mime = when {
                            lower.contains(".m3u8") -> "application/vnd.apple.mpegurl"
                            lower.contains(".mpd") -> "application/dash+xml"
                            Regex("\\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)(?:[?#]|$)").containsMatchIn(lower) -> "audio/*"
                            else -> "video/*"
                        }
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(url), mime)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("title", URLUtil.guessFileName(url, null, mime))
                            find(a.window.decorView, WebView::class.java)?.url?.takeIf { it.startsWith("http") }?.let { putExtra("referer", it) }
                        }
                        a.startActivity(Intent.createChooser(intent, "Play media with"))
                    }.onFailure { toast(a, "No compatible player") }'''
if old not in s:
    raise SystemExit('target media action not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('patched persistent media external action to baseline typed intent')
