package com.ruzakj.ricbrowser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object PowerTools {
    private const val PREFS = "ric_power_tools"
    private const val KEY_SEARCH = "search_engine"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val D = "desktop_"
    private const val J = "js_"
    private const val I = "images_"
    private const val C = "cookies_"
    private const val U = "ua_"
    private val attached = java.util.WeakHashMap<Activity, Boolean>()

    fun attach(activity: Activity) {
        if (activity !is AppCompatActivity || attached[activity] == true) return
        val web = find(activity.window.decorView, WebView::class.java) ?: return
        attached[activity] = true
        addButton(activity, web)
        addGestures(activity, web)
        hookAddress(activity, web)
        applySite(activity, web)
    }

    private fun addButton(a: AppCompatActivity, w: WebView) {
        val parent = a.window.decorView as? ViewGroup ?: return
        val b = Button(a).apply {
            text = "⚡"; textSize = 18f; isAllCaps = false; alpha = .9f
            setOnClickListener { toolbox(a, w) }
            setOnLongClickListener { profiles(a, w); true }
        }
        val s = dp(a, 48)
        val lp = if (parent is FrameLayout) FrameLayout.LayoutParams(s, s, Gravity.END or Gravity.BOTTOM).apply { marginEnd = dp(a, 14); bottomMargin = dp(a, 22) } else ViewGroup.LayoutParams(s, s)
        parent.addView(b, lp)
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
        val h = host(w.url); val p = prefs(a)
        val items = arrayOf(
            "Site controls", "Find in page", "Reader mode", "Desktop mode: ${on(p.getBoolean(D+h,false))}",
            "JavaScript: ${on(p.getBoolean(J+h,true))}", "Images: ${on(p.getBoolean(I+h,true))}", "Translate page",
            "Share / copy clean URL", "Bookmark this page", "Bookmarks", "Save offline (.mht)", "Screenshot page",
            "Page source", "Developer info", "Search engine", "Open incognito", "Clear site data", "Site profiles"
        )
        AlertDialog.Builder(a).setTitle("Ric Toolbox • ${h.ifBlank { "Page" }}").setItems(items) { _, i -> when(i) {
            0 -> controls(a,w); 1 -> findInPage(a,w); 2 -> reader(a,w); 3 -> toggleDesktop(a,w); 4 -> toggleJs(a,w); 5 -> toggleImages(a,w)
            6 -> translate(w); 7 -> share(a,w); 8 -> bookmark(a,w); 9 -> bookmarks(a,w); 10 -> saveOffline(a,w); 11 -> screenshot(a,w)
            12 -> source(a,w); 13 -> devInfo(a,w); 14 -> searchEngine(a); 15 -> a.startActivity(Intent(a, IncognitoActivity::class.java).putExtra("url", w.url))
            16 -> clearSite(a,w); 17 -> profiles(a,w)
        }}.show()
    }

    private fun controls(a: AppCompatActivity, w: WebView) {
        val h = host(w.url); val p = prefs(a); val msg = "Site: $h\nJavaScript: ${on(w.settings.javaScriptEnabled)}\nImages: ${on(w.settings.loadsImagesAutomatically)}\nCookies: ${on(p.getBoolean(C+h,true))}\nUser-Agent: ${if (p.getString(U+h,"").orEmpty().isBlank()) "Default" else "Custom"}\n\nBaseline ad/tracker blocker remains active."
        AlertDialog.Builder(a).setTitle("Site controls").setMessage(msg).setItems(arrayOf("Toggle cookies","Set custom user-agent","Reset site settings")) { _,i -> when(i){0->toggleCookies(a,w);1->customUa(a,w);2->reset(a,w)}}.setNegativeButton("Close",null).show()
    }

    private fun findInPage(a: AppCompatActivity, w: WebView) {
        val e = EditText(a).apply { hint="Find text"; setSingleLine(true) }
        AlertDialog.Builder(a).setTitle("Find in page").setView(e).setPositiveButton("Find") { _,_-> w.findAllAsync(e.text.toString()); w.showFindDialog(e.text.toString(), true) }.setNegativeButton("Cancel",null).show()
    }

    private fun reader(a: AppCompatActivity, w: WebView) {
        val js = "(()=>{let o=document.getElementById('__ric_reader');if(o){o.remove();return'off'};let s=document.createElement('style');s.id='__ric_reader';s.textContent='body{max-width:820px!important;margin:auto!important;padding:24px!important;font-size:19px!important;line-height:1.65!important;background:#fafafa!important;color:#202124!important}nav,header,footer,aside,.adsbygoogle,iframe{display:none!important}img,video{max-width:100%!important;height:auto!important}';document.documentElement.appendChild(s);return'on'})()"
        w.evaluateJavascript(js){ toast(a, if(it.contains("on")) "Reader mode on" else "Reader mode off") }
    }

    private fun toggleDesktop(a: AppCompatActivity, w: WebView) { val h=host(w.url); val p=prefs(a); val v=!p.getBoolean(D+h,false); p.edit().putBoolean(D+h,v).apply(); desktop(w,v,p.getString(U+h,"").orEmpty()); w.reload() }
    private fun toggleJs(a: AppCompatActivity, w: WebView) { val h=host(w.url); val p=prefs(a); val v=!w.settings.javaScriptEnabled; w.settings.javaScriptEnabled=v; p.edit().putBoolean(J+h,v).apply(); w.reload() }
    private fun toggleImages(a: AppCompatActivity, w: WebView) { val h=host(w.url); val p=prefs(a); val v=!w.settings.loadsImagesAutomatically; w.settings.loadsImagesAutomatically=v; w.settings.blockNetworkImage=!v; p.edit().putBoolean(I+h,v).apply(); w.reload() }
    private fun toggleCookies(a: AppCompatActivity, w: WebView) { val h=host(w.url); val p=prefs(a); val v=!p.getBoolean(C+h,true); p.edit().putBoolean(C+h,v).apply(); CookieManager.getInstance().setAcceptCookie(v); CookieManager.getInstance().setAcceptThirdPartyCookies(w,v); toast(a,"Cookies ${on(v)}") }
    private fun translate(w: WebView) { w.url?.let { w.loadUrl("https://translate.google.com/translate?sl=auto&tl=id&u="+Uri.encode(it)) } }

    private fun share(a: AppCompatActivity, w: WebView) {
        val u=clean(w.url?:return); AlertDialog.Builder(a).setTitle(u).setItems(arrayOf("Share","Copy")){_,i-> if(i==0)a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,u)},"Share link")) else copy(a,u)}.show()
    }

    private fun bookmark(a: AppCompatActivity,w:WebView){val u=w.url?:return;val t=w.title?.take(80).orEmpty().ifBlank{host(u)};val p=prefs(a);val s=p.getStringSet(KEY_BOOKMARKS,emptySet())?.toMutableSet()?:mutableSetOf();s.add("$t\t$u");p.edit().putStringSet(KEY_BOOKMARKS,s).apply();toast(a,"Bookmarked")}
    private fun bookmarks(a:AppCompatActivity,w:WebView){val p=prefs(a);val v=p.getStringSet(KEY_BOOKMARKS,emptySet()).orEmpty().sorted();if(v.isEmpty()){toast(a,"No bookmarks");return};AlertDialog.Builder(a).setTitle("Bookmarks").setItems(v.map{it.substringBefore('\t')}.toTypedArray()){_,i->w.loadUrl(v[i].substringAfter('\t'))}.setNeutralButton("Clear all"){_,_->p.edit().remove(KEY_BOOKMARKS).apply()}.setNegativeButton("Close",null).show()}

    private fun saveOffline(a:AppCompatActivity,w:WebView){val root=a.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?:a.filesDir;val d=File(root,"RicBrowserOffline").apply{mkdirs()};val n="page_${System.currentTimeMillis()}.mht";w.saveWebArchive(File(d,n).absolutePath,false){toast(a,if(it.isNullOrBlank())"Offline save failed" else "Saved offline: $n")}}
    private fun screenshot(a:AppCompatActivity,w:WebView){try{val bw=w.width.coerceAtLeast(1);val bh=(w.contentHeight*w.scale).toInt().coerceAtLeast(w.height).coerceAtMost(12000);val b=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888);w.draw(Canvas(b));val root=a.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?:a.cacheDir;val d=File(root,"RicBrowserScreenshots").apply{mkdirs()};val f=File(d,"ric_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())}.png");FileOutputStream(f).use{b.compress(Bitmap.CompressFormat.PNG,92,it)};b.recycle();toast(a,"Screenshot saved: ${f.name}")}catch(_:Throwable){toast(a,"Screenshot failed")}}
    private fun source(a:AppCompatActivity,w:WebView){w.evaluateJavascript("document.documentElement.outerHTML"){r->val t=r.orEmpty().replace("\\n","\n").replace("\\\"","\"").trim('"').take(50000);AlertDialog.Builder(a).setTitle("Page source (first 50 KB)").setMessage(t).setPositiveButton("Copy"){_,_->copy(a,t)}.setNegativeButton("Close",null).show()}}
    private fun devInfo(a:AppCompatActivity,w:WebView){val h=w.copyBackForwardList();AlertDialog.Builder(a).setTitle("Developer info").setMessage("URL: ${w.url}\nTitle: ${w.title}\nHistory entries: ${h.size}\nJavaScript: ${w.settings.javaScriptEnabled}\nImages: ${w.settings.loadsImagesAutomatically}\n\nUser-Agent:\n${w.settings.userAgentString}").setPositiveButton("Copy URL"){_,_->copy(a,w.url.orEmpty())}.setNegativeButton("Close",null).show()}

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

    private fun <T:View> find(root:View, cls:Class<T>):T?{
        val q:ArrayDeque<View> = ArrayDeque(); q.add(root)
        while(q.isNotEmpty()){val v=q.removeFirst();if(cls.isInstance(v))return cls.cast(v);if(v is ViewGroup)for(i in 0 until v.childCount)q.add(v.getChildAt(i))}
        return null
    }
}

class IncognitoActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private fun px(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};val close=Button(this).apply{text="×";setOnClickListener{finish()}};val title=TextView(this).apply{text="Incognito • Ric Browser";textSize=16f;setPadding(16,0,0,0)};bar.addView(close,LinearLayout.LayoutParams(px(48),px(48)));bar.addView(title,LinearLayout.LayoutParams(0,px(48),1f));webView=WebView(this);root.addView(bar);root.addView(webView,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root)
        CookieManager.getInstance().setAcceptCookie(false);CookieManager.getInstance().setAcceptThirdPartyCookies(webView,false);webView.settings.javaScriptEnabled=true;webView.settings.domStorageEnabled=false;webView.settings.cacheMode=WebSettings.LOAD_NO_CACHE;webView.settings.userAgentString=webView.settings.userAgentString.replace("; wv","");webView.webViewClient=android.webkit.WebViewClient();webView.loadUrl(intent.getStringExtra("url")?:"https://www.google.com")
    }
    override fun onBackPressed(){if(::webView.isInitialized&&webView.canGoBack())webView.goBack() else super.onBackPressed()}
    override fun onDestroy(){if(::webView.isInitialized){webView.clearHistory();webView.clearCache(true);webView.clearFormData();webView.loadUrl("about:blank");webView.destroy()};CookieManager.getInstance().removeSessionCookies(null);super.onDestroy()}
}
