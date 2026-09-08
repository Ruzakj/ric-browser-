package com.ruzakj.ricbrowser

import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.WeakHashMap

object PowerTools {
    private const val PREFS = "ric_power_tools"
    private const val KEY_SEARCH = "search_engine"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_MEDIA = "persistent_media"
    private const val D = "desktop_"
    private const val J = "js_"
    private const val I = "images_"
    private const val C = "cookies_"
    private const val U = "ua_"
    private const val POLL_MS = 2500L
    private const val MAX_MEDIA = 120

    private val attached = WeakHashMap<Activity, Boolean>()
    private val pollers = WeakHashMap<Activity, Runnable>()
    private val lastUrl = WeakHashMap<Activity, String>()
    private val media = LinkedHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())

    fun attach(activity: Activity) {
        if (activity !is AppCompatActivity) return
        val web = find(activity.window.decorView, WebView::class.java) ?: return
        if (attached[activity] != true) {
            attached[activity] = true
            restoreMedia(activity)
            hookTabHold(activity)
            hookMediaButton(activity)
            addGestures(activity, web)
            hookAddress(activity, web)
            applySite(activity, web)
            applyChromeDark(activity, prefs(activity).getBoolean(KEY_DARK, false))
            startPoller(activity)
        } else {
            hookTabHold(activity)
            hookMediaButton(activity)
        }
        updateMediaButton(activity)
    }

    private fun startPoller(a: AppCompatActivity) {
        pollers[a]?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (a.isFinishing || a.isDestroyed) return
                val w = find(a.window.decorView, WebView::class.java)
                if (w != null) {
                    val u = w.url.orEmpty()
                    if (lastUrl[a] != u) {
                        lastUrl[a] = u
                        applySite(a, w)
                        if (prefs(a).getBoolean(KEY_DARK, false)) applyPageDark(w, true)
                    }
                    scanMedia(a, w)
                    hookTabHold(a)
                    hookMediaButton(a)
                }
                handler.postDelayed(this, POLL_MS)
            }
        }
        pollers[a] = r
        handler.post(r)
    }

    private fun hookTabHold(a: AppCompatActivity) {
        val buttons = mutableListOf<Button>()
        collect(a.window.decorView, Button::class.java, buttons)
        val tab = buttons.firstOrNull { it.text?.toString()?.startsWith("□") == true } ?: return
        tab.setOnLongClickListener {
            find(a.window.decorView, WebView::class.java)?.let { toolbox(a, it) }
            true
        }
    }

    private fun hookMediaButton(a: AppCompatActivity) {
        val buttons = mutableListOf<Button>()
        collect(a.window.decorView, Button::class.java, buttons)
        val b = buttons.firstOrNull { it.text?.toString()?.startsWith("↓") == true } ?: return
        b.setOnClickListener { showPersistentMedia(a) }
    }

    private fun updateMediaButton(a: AppCompatActivity) {
        val buttons = mutableListOf<Button>()
        collect(a.window.decorView, Button::class.java, buttons)
        val b = buttons.firstOrNull { it.text?.toString()?.startsWith("↓") == true } ?: return
        val n = media.size
        b.text = if (n > 0) "↓$n" else "↓"
        b.isEnabled = n > 0
        b.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    private fun scanMedia(a: AppCompatActivity, w: WebView) {
        val js = """
            (()=>{const o=new Set();const add=v=>{if(!v||typeof v!=='string')return;try{v=new URL(v,location.href).href}catch(_){return}if(/^https?:/i.test(v))o.add(v)};document.querySelectorAll('video,audio,source').forEach(e=>{add(e.currentSrc);add(e.src);add(e.getAttribute&&e.getAttribute('src'))});try{performance.getEntriesByType('resource').forEach(r=>{if(/\.(mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|oga|opus|wav|flac|m3u8|mpd)(?:[?#]|$)/i.test(r.name)||/googlevideo\.com\/videoplayback/i.test(r.name))add(r.name)})}catch(_){}return JSON.stringify(Array.from(o));})()
        """.trimIndent()
        runCatching {
            w.evaluateJavascript(js) { raw ->
                if (raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
                runCatching {
                    val decoded = JSONTokener(raw).nextValue()
                    val text = if (decoded is String) decoded else decoded.toString()
                    val arr = JSONArray(text)
                    var changed = false
                    for (i in 0 until arr.length()) {
                        val url = arr.optString(i).trim()
                        if (url.startsWith("http") && isMedia(url) && !media.containsKey(url)) {
                            if (media.size >= MAX_MEDIA) media.remove(media.keys.firstOrNull())
                            media[url] = mediaLabel(url)
                            changed = true
                        }
                    }
                    if (changed) {
                        persistMedia(a)
                        updateMediaButton(a)
                    }
                }
            }
        }
    }

    private fun isMedia(url: String): Boolean {
        val s = url.lowercase(Locale.ROOT)
        return s.contains(".m3u8") || s.contains(".mpd") || s.contains("googlevideo.com/videoplayback") ||
            Regex("\\.(mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|oga|opus|wav|flac)(?:[?#]|$)").containsMatchIn(s)
    }

    private fun mediaLabel(url: String): String {
        val s = url.lowercase(Locale.ROOT)
        val type = when {
            s.contains(".m3u8") || s.contains(".mpd") -> "STREAM"
            Regex("\\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)(?:[?#]|$)").containsMatchIn(s) -> "AUDIO"
            else -> "VIDEO"
        }
        return "$type • ${URLUtil.guessFileName(url, null, null)}"
    }

    private fun persistMedia(a: Context) {
        prefs(a).edit().putStringSet(KEY_MEDIA, media.keys.toSet()).apply()
    }

    private fun restoreMedia(a: Context) {
        if (media.isNotEmpty()) return
        prefs(a).getStringSet(KEY_MEDIA, emptySet()).orEmpty().take(MAX_MEDIA).forEach { media[it] = mediaLabel(it) }
    }

    private fun showPersistentMedia(a: AppCompatActivity) {
        if (media.isEmpty()) { toast(a, "No media detected"); return }
        val entries = media.entries.toList()
        AlertDialog.Builder(a).setTitle("Media (${entries.size}) • persistent")
            .setItems(entries.map { it.value }.toTypedArray()) { _, i -> mediaActions(a, entries[i].key) }
            .setNeutralButton("Clear list") { _, _ -> media.clear(); persistMedia(a); updateMediaButton(a) }
            .setNegativeButton("Close", null).show()
    }

    private fun mediaActions(a: AppCompatActivity, url: String) {
        AlertDialog.Builder(a).setTitle(URLUtil.guessFileName(url, null, null))
            .setItems(arrayOf("Play external", "Download", "Copy link", "Remove from list")) { _, i ->
                when (i) {
                    0 -> runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast(a, "No compatible player") }
                    1 -> download(a, url)
                    2 -> copy(a, url)
                    3 -> { media.remove(url); persistMedia(a); updateMediaButton(a) }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun download(a: Context, url: String) {
        runCatching {
            val name = URLUtil.guessFileName(url, null, null)
            val req = DownloadManager.Request(Uri.parse(url)).setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            (a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast(a, "Download started")
        }.onFailure { toast(a, "Download failed") }
    }

    private fun addGestures(a: AppCompatActivity, w: WebView) {
        var x = 0f; var y = 0f
        w.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { x = e.x; y = e.y }
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val dx = e.x - x; val dy = e.y - y
                if (kotlin.math.abs(dx) > dp(a, 130) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                    if (dx > 0 && w.canGoBack()) w.goBack() else if (dx < 0 && w.canGoForward()) w.goForward()
                }
            }
            false
        }
    }

    private fun hookAddress(a: AppCompatActivity, w: WebView) {
        val e = find(a.window.decorView, EditText::class.java) ?: return
        e.setOnEditorActionListener { v, _, _ ->
            val raw = v.text.toString().trim()
            if (raw.isNotEmpty()) {
                val url = when {
                    raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                    raw.contains('.') && !raw.contains(' ') -> "https://$raw"
                    else -> searchUrl(a, raw)
                }
                w.loadUrl(url); v.clearFocus()
            }
            true
        }
    }

    private fun toolbox(a: AppCompatActivity, w: WebView) {
        val h = host(w.url); val p = prefs(a); val dark = p.getBoolean(KEY_DARK, false)
        val items = arrayOf(
            "Dark mode: ${on(dark)}", "Site controls", "Find in page", "Reader mode",
            "Desktop mode: ${on(p.getBoolean(D+h,false))}", "JavaScript: ${on(p.getBoolean(J+h,true))}",
            "Images: ${on(p.getBoolean(I+h,true))}", "Translate page", "Share / copy clean URL",
            "Bookmark this page", "Bookmarks", "Save offline (.mht)", "Screenshot page", "Page source",
            "Developer info", "Search engine", "Open incognito", "Clear site data", "Site profiles", "Clear media scan list"
        )
        AlertDialog.Builder(a).setTitle("Ric Toolbox • hold Tabs").setItems(items) { _, i -> when(i) {
            0 -> toggleDark(a,w); 1 -> controls(a,w); 2 -> findInPage(a,w); 3 -> reader(a,w); 4 -> toggleDesktop(a,w)
            5 -> toggleJs(a,w); 6 -> toggleImages(a,w); 7 -> translate(w); 8 -> share(a,w); 9 -> bookmark(a,w)
            10 -> bookmarks(a,w); 11 -> saveOffline(a,w); 12 -> screenshot(a,w); 13 -> source(a,w); 14 -> devInfo(a,w)
            15 -> searchEngine(a); 16 -> a.startActivity(Intent(a, IncognitoActivity::class.java).putExtra("url",w.url));
            17 -> clearSite(a,w); 18 -> profiles(a,w); 19 -> { media.clear(); persistMedia(a); updateMediaButton(a); toast(a,"Media list cleared") }
        }}.show()
    }

    private fun toggleDark(a: AppCompatActivity, w: WebView) {
        val p = prefs(a); val v = !p.getBoolean(KEY_DARK, false)
        p.edit().putBoolean(KEY_DARK, v).apply()
        applyChromeDark(a, v); applyPageDark(w, v)
        toast(a, "Dark mode ${on(v)}")
    }

    private fun applyPageDark(w: WebView, enabled: Boolean) {
        val js = if (enabled) """
            (()=>{let s=document.getElementById('__ric_dark');if(!s){s=document.createElement('style');s.id='__ric_dark';document.documentElement.appendChild(s)}s.textContent='html,body{background:#111318!important;color:#e8eaed!important}body *:not(img):not(video):not(canvas):not(svg){border-color:#3c4043!important}a{color:#8ab4f8!important}input,textarea,select,button{background:#202124!important;color:#e8eaed!important}';document.documentElement.style.colorScheme='dark';return true})()
        """.trimIndent() else "(()=>{let s=document.getElementById('__ric_dark');if(s)s.remove();document.documentElement.style.colorScheme='';return true})()"
        runCatching { w.evaluateJavascript(js, null) }
    }

    private fun applyChromeDark(a: AppCompatActivity, enabled: Boolean) {
        val bg = if (enabled) Color.rgb(30,30,30) else Color.rgb(250,250,250)
        val fg = if (enabled) Color.rgb(238,238,238) else Color.rgb(28,28,28)
        val tint = if (enabled) Color.rgb(48,49,52) else Color.rgb(247,247,247)
        a.window.statusBarColor = bg; a.window.navigationBarColor = bg
        fun walk(v: View) {
            when (v) {
                is LinearLayout -> v.setBackgroundColor(bg)
                is Button -> { v.setTextColor(fg); v.backgroundTintList = ColorStateList.valueOf(tint) }
                is EditText -> { v.setTextColor(fg); v.setHintTextColor(if(enabled) Color.LTGRAY else Color.DKGRAY); v.backgroundTintList = ColorStateList.valueOf(tint) }
                is TextView -> v.setTextColor(fg)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.window.decorView)
    }

    private fun controls(a: AppCompatActivity, w: WebView) {
        val h=host(w.url);val p=prefs(a);val msg="Site: $h\nJavaScript: ${on(w.settings.javaScriptEnabled)}\nImages: ${on(w.settings.loadsImagesAutomatically)}\nCookies: ${on(p.getBoolean(C+h,true))}\nUser-Agent: ${if(p.getString(U+h,"").orEmpty().isBlank())"Default" else "Custom"}"
        AlertDialog.Builder(a).setTitle("Site controls").setMessage(msg).setItems(arrayOf("Toggle cookies","Set custom user-agent","Reset site settings")){_,i->when(i){0->toggleCookies(a,w);1->customUa(a,w);2->reset(a,w)}}.setNegativeButton("Close",null).show()
    }
    private fun findInPage(a:AppCompatActivity,w:WebView){val e=EditText(a).apply{hint="Find text";setSingleLine(true)};AlertDialog.Builder(a).setTitle("Find in page").setView(e).setPositiveButton("Find"){_,_->w.findAllAsync(e.text.toString());w.showFindDialog(e.text.toString(),true)}.setNegativeButton("Cancel",null).show()}
    private fun reader(a:AppCompatActivity,w:WebView){val js="(()=>{let o=document.getElementById('__ric_reader');if(o){o.remove();return'off'};let s=document.createElement('style');s.id='__ric_reader';s.textContent='body{max-width:820px!important;margin:auto!important;padding:24px!important;font-size:19px!important;line-height:1.65!important}nav,header,footer,aside,.adsbygoogle,iframe{display:none!important}img,video{max-width:100%!important;height:auto!important}';document.documentElement.appendChild(s);return'on'})()";w.evaluateJavascript(js){toast(a,if(it.contains("on"))"Reader mode on" else "Reader mode off")}}
    private fun toggleDesktop(a:AppCompatActivity,w:WebView){val h=host(w.url);val p=prefs(a);val v=!p.getBoolean(D+h,false);p.edit().putBoolean(D+h,v).apply();desktop(w,v,p.getString(U+h,"").orEmpty());w.reload()}
    private fun toggleJs(a:AppCompatActivity,w:WebView){val h=host(w.url);val p=prefs(a);val v=!w.settings.javaScriptEnabled;w.settings.javaScriptEnabled=v;p.edit().putBoolean(J+h,v).apply();w.reload()}
    private fun toggleImages(a:AppCompatActivity,w:WebView){val h=host(w.url);val p=prefs(a);val v=!w.settings.loadsImagesAutomatically;w.settings.loadsImagesAutomatically=v;w.settings.blockNetworkImage=!v;p.edit().putBoolean(I+h,v).apply();w.reload()}
    private fun toggleCookies(a:AppCompatActivity,w:WebView){val h=host(w.url);val p=prefs(a);val v=!p.getBoolean(C+h,true);p.edit().putBoolean(C+h,v).apply();CookieManager.getInstance().setAcceptCookie(v);CookieManager.getInstance().setAcceptThirdPartyCookies(w,v);toast(a,"Cookies ${on(v)}")}
    private fun translate(w:WebView){w.url?.let{w.loadUrl("https://translate.google.com/translate?sl=auto&tl=id&u="+Uri.encode(it))}}
    private fun share(a:AppCompatActivity,w:WebView){val u=clean(w.url?:return);AlertDialog.Builder(a).setTitle(u).setItems(arrayOf("Share","Copy")){_,i->if(i==0)a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,u)},"Share link"))else copy(a,u)}.show()}
    private fun bookmark(a:AppCompatActivity,w:WebView){val u=w.url?:return;val t=w.title?.take(80).orEmpty().ifBlank{host(u)};val p=prefs(a);val s=p.getStringSet(KEY_BOOKMARKS,emptySet())?.toMutableSet()?:mutableSetOf();s.add("$t\t$u");p.edit().putStringSet(KEY_BOOKMARKS,s).apply();toast(a,"Bookmarked")}
    private fun bookmarks(a:AppCompatActivity,w:WebView){val p=prefs(a);val v=p.getStringSet(KEY_BOOKMARKS,emptySet()).orEmpty().sorted();if(v.isEmpty()){toast(a,"No bookmarks");return};AlertDialog.Builder(a).setTitle("Bookmarks").setItems(v.map{it.substringBefore('\t')}.toTypedArray()){_,i->w.loadUrl(v[i].substringAfter('\t'))}.setNeutralButton("Clear all"){_,_->p.edit().remove(KEY_BOOKMARKS).apply()}.setNegativeButton("Close",null).show()}
    private fun saveOffline(a:AppCompatActivity,w:WebView){val root=a.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?:a.filesDir;val d=File(root,"RicBrowserOffline").apply{mkdirs()};val n="page_${System.currentTimeMillis()}.mht";w.saveWebArchive(File(d,n).absolutePath,false){toast(a,if(it.isNullOrBlank())"Offline save failed" else "Saved offline: $n")}}
    private fun screenshot(a:AppCompatActivity,w:WebView){try{val bw=w.width.coerceAtLeast(1);val bh=(w.contentHeight*w.scale).toInt().coerceAtLeast(w.height).coerceAtMost(12000);val b=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888);w.draw(Canvas(b));val root=a.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?:a.cacheDir;val d=File(root,"RicBrowserScreenshots").apply{mkdirs()};val f=File(d,"ric_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())}.png");FileOutputStream(f).use{b.compress(Bitmap.CompressFormat.PNG,92,it)};b.recycle();toast(a,"Screenshot saved: ${f.name}")}catch(_:Throwable){toast(a,"Screenshot failed")}}
    private fun source(a:AppCompatActivity,w:WebView){w.evaluateJavascript("document.documentElement.outerHTML"){r->val t=r.orEmpty().replace("\\n","\n").replace("\\\"","\"").trim('"').take(50000);AlertDialog.Builder(a).setTitle("Page source (first 50 KB)").setMessage(t).setPositiveButton("Copy"){_,_->copy(a,t)}.setNegativeButton("Close",null).show()}}
    private fun devInfo(a:AppCompatActivity,w:WebView){val h=w.copyBackForwardList();AlertDialog.Builder(a).setTitle("Developer info").setMessage("URL: ${w.url}\nTitle: ${w.title}\nHistory entries: ${h.size}\nJavaScript: ${w.settings.javaScriptEnabled}\nImages: ${w.settings.loadsImagesAutomatically}\nMedia retained: ${media.size}\n\nUser-Agent:\n${w.settings.userAgentString}").setPositiveButton("Copy URL"){_,_->copy(a,w.url.orEmpty())}.setNegativeButton("Close",null).show()}
    private fun searchEngine(a:AppCompatActivity){val n=arrayOf("Google","DuckDuckGo","Bing","Brave Search");val v=arrayOf("google","duck","bing","brave");val p=prefs(a);val c=v.indexOf(p.getString(KEY_SEARCH,"google")).coerceAtLeast(0);AlertDialog.Builder(a).setTitle("Search engine").setSingleChoiceItems(n,c){d,i->p.edit().putString(KEY_SEARCH,v[i]).apply();d.dismiss();toast(a,"Search: ${n[i]}")}.show()}
    private fun clearSite(a:AppCompatActivity,w:WebView){val u=w.url?:return;CookieManager.getInstance().setCookie(u,"");CookieManager.getInstance().removeSessionCookies(null);w.clearCache(true);w.clearFormData();toast(a,"Site data cleared")}
    private fun profiles(a:AppCompatActivity,w:WebView){val n=arrayOf("Normal","Fast","Private","Desktop","Media");AlertDialog.Builder(a).setTitle("Site profile").setItems(n){_,i->when(i){0->profile(a,w,true,true,true,false);1->profile(a,w,true,false,true,false);2->profile(a,w,true,true,false,false);3->profile(a,w,true,true,true,true);4->profile(a,w,true,true,true,false)}}.show()}
    private fun profile(a:AppCompatActivity,w:WebView,js:Boolean,img:Boolean,cookies:Boolean,desk:Boolean){val h=host(w.url);prefs(a).edit().putBoolean(J+h,js).putBoolean(I+h,img).putBoolean(C+h,cookies).putBoolean(D+h,desk).apply();applySite(a,w);w.reload()}
    private fun customUa(a:AppCompatActivity,w:WebView){val h=host(w.url);val p=prefs(a);val e=EditText(a).apply{setText(p.getString(U+h,""));hint="Leave empty for default"};AlertDialog.Builder(a).setTitle("Custom user-agent").setView(e).setPositiveButton("Save"){_,_->p.edit().putString(U+h,e.text.toString().trim()).apply();applySite(a,w);w.reload()}.setNegativeButton("Cancel",null).show()}
    private fun reset(a:AppCompatActivity,w:WebView){val h=host(w.url);prefs(a).edit().remove(D+h).remove(J+h).remove(I+h).remove(C+h).remove(U+h).apply();w.settings.javaScriptEnabled=true;w.settings.loadsImagesAutomatically=true;w.settings.blockNetworkImage=false;CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(w,true);desktop(w,false,"");w.reload();toast(a,"Site settings reset")}
    private fun applySite(a:AppCompatActivity,w:WebView){val h=host(w.url);if(h.isBlank())return;val p=prefs(a);val js=p.getBoolean(J+h,true);val img=p.getBoolean(I+h,true);val co=p.getBoolean(C+h,true);w.settings.javaScriptEnabled=js;w.settings.loadsImagesAutomatically=img;w.settings.blockNetworkImage=!img;CookieManager.getInstance().setAcceptCookie(co);CookieManager.getInstance().setAcceptThirdPartyCookies(w,co);desktop(w,p.getBoolean(D+h,false),p.getString(U+h,"").orEmpty())}
    private fun desktop(w:WebView,v:Boolean,ua:String){val def=WebSettings.getDefaultUserAgent(w.context).replace("; wv","");w.settings.userAgentString=when{ua.isNotBlank()->ua;v->"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";else->def};w.settings.useWideViewPort=v;w.settings.loadWithOverviewMode=v}
    private fun searchUrl(c:Context,q:String):String{val x=Uri.encode(q);return when(prefs(c).getString(KEY_SEARCH,"google")){"duck"->"https://duckduckgo.com/?q=$x";"bing"->"https://www.bing.com/search?q=$x";"brave"->"https://search.brave.com/search?q=$x";else->"https://www.google.com/search?q=$x"}}
    private fun clean(raw:String):String=try{val u=Uri.parse(raw);val b=u.buildUpon().clearQuery();val bad=setOf("utm_source","utm_medium","utm_campaign","utm_term","utm_content","fbclid","gclid","mc_cid","mc_eid");u.queryParameterNames.filterNot{it.lowercase(Locale.ROOT) in bad}.forEach{k->u.getQueryParameters(k).forEach{v->b.appendQueryParameter(k,v)}};b.build().toString()}catch(_:Exception){raw}
    private fun host(url:String?):String=try{Uri.parse(url?:"").host?.removePrefix("www.")?.lowercase(Locale.ROOT).orEmpty()}catch(_:Exception){""}
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun on(v:Boolean)=if(v)"ON" else "OFF"
    private fun copy(c:Context,t:String){(c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Ric Browser",t));toast(c,"Copied")}
    private fun toast(c:Context,t:String)=Toast.makeText(c,t,Toast.LENGTH_SHORT).show()
    private fun dp(c:Context,v:Int)=(v*c.resources.displayMetrics.density).toInt()
    private fun <T:View> find(root:View,type:Class<T>):T?{if(type.isInstance(root))return type.cast(root);if(root is ViewGroup)for(i in 0 until root.childCount){val r=find(root.getChildAt(i),type);if(r!=null)return r};return null}
    private fun <T:View> collect(root:View,type:Class<T>,out:MutableList<T>){if(type.isInstance(root))type.cast(root)?.let{out.add(it)};if(root is ViewGroup)for(i in 0 until root.childCount)collect(root.getChildAt(i),type,out)}
}

class IncognitoActivity:AppCompatActivity(){private lateinit var w:WebView;override fun onCreate(b:android.os.Bundle?){super.onCreate(b);val r=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val x=Button(this).apply{text="×";setOnClickListener{finish()}};val t=TextView(this).apply{text="Incognito • Ric Browser";textSize=16f};bar.addView(x,LinearLayout.LayoutParams(48,48));bar.addView(t,LinearLayout.LayoutParams(0,48,1f));w=WebView(this);r.addView(bar);r.addView(w,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(r);CookieManager.getInstance().setAcceptCookie(false);CookieManager.getInstance().setAcceptThirdPartyCookies(w,false);w.settings.javaScriptEnabled=true;w.settings.domStorageEnabled=false;w.settings.cacheMode=WebSettings.LOAD_NO_CACHE;w.webViewClient=android.webkit.WebViewClient();w.loadUrl(intent.getStringExtra("url")?:"https://www.google.com")};override fun onBackPressed(){if(::w.isInitialized&&w.canGoBack())w.goBack()else super.onBackPressed()};override fun onDestroy(){if(::w.isInitialized){w.clearHistory();w.clearCache(true);w.loadUrl("about:blank");w.destroy()};CookieManager.getInstance().removeSessionCookies(null);super.onDestroy()}}
