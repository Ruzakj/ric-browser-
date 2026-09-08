from pathlib import Path

p = Path('app/src/main/java/com/ruzakj/ricbrowser/PowerTools.kt')
s = p.read_text(encoding='utf-8')
old = '0 -> runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast(a, "No compatible player") }'
new = '''0 -> {
                        val lower = url.lowercase(Locale.ROOT)
                        val mime = when {
                            lower.contains(".m3u8") -> "application/vnd.apple.mpegurl"
                            lower.contains(".mpd") -> "application/dash+xml"
                            listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac").any { lower.contains(it) } -> "audio/*"
                            else -> "video/*"
                        }
                        val base = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(url), mime)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("title", URLUtil.guessFileName(url, null, mime))
                            find(a.window.decorView, WebView::class.java)?.url?.takeIf { it.startsWith("http") }?.let {
                                putExtra("referer", it)
                                putExtra("headers", arrayOf("Referer", it))
                            }
                        }
                        val opened = listOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro").any { pkg ->
                            runCatching {
                                a.startActivity(Intent(base).setPackage(pkg))
                                true
                            }.getOrDefault(false)
                        }
                        if (!opened) toast(a, "MX Player / MX Player Pro not installed")
                    }'''
if old not in s:
    raise SystemExit('persistent media External target not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('PowerTools Play external hard-routed to MX Player Free/Pro')
