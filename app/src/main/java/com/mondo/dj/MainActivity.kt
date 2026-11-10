package com.mondo.dj

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.view.View

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var tuneBadMode = false
    private var pendingPlaylist = ""

    inner class MondoBridge {
        @JavascriptInterface
        fun openTuneBad(playlistUrl: String) {
            runOnUiThread {
                pendingPlaylist = playlistUrl
                tuneBadMode = true
                web.loadUrl("https://www.tunebad.com/playlist-analyzer")
            }
        }

        @JavascriptInterface
        fun importResults(json: String) {
            runOnUiThread {
                tuneBadMode = false
                web.loadUrl("file:///android_asset/index.html")
                web.postDelayed({
                    val safe = org.json.JSONObject.quote(json)
                    web.evaluateJavascript("window.receiveTuneBadData && window.receiveTuneBadData($safe);", null)
                }, 700)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        web.addJavascriptInterface(MondoBridge(), "MondoBridge")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (tuneBadMode && url != null && url.contains("tunebad.com/playlist-analyzer")) {
                    injectTuneBadHelper()
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun injectTuneBadHelper() {
        val playlist = org.json.JSONObject.quote(pendingPlaylist)
        val script = """
            (function(){
              if(window.__mondoTuneBadHelper) return;
              window.__mondoTuneBadHelper=true;
              const url=$playlist;
              function start(){
                const inputs=Array.from(document.querySelectorAll('input')).filter(x=>x.type!=='hidden');
                const field=inputs.find(x=>/playlist|spotify|youtube|link/i.test((x.placeholder||'')+' '+(x.getAttribute('aria-label')||'')+' '+(x.name||''))) || inputs[0];
                if(field){
                  field.value=url;
                  field.dispatchEvent(new Event('input',{bubbles:true}));
                  field.dispatchEvent(new Event('change',{bubbles:true}));
                  const btn=Array.from(document.querySelectorAll('button')).find(b=>/analyze playlist/i.test(b.innerText||b.textContent||''));
                  if(btn) btn.click();
                }
                addImportButton();
                const observer=new MutationObserver(addImportButton);
                observer.observe(document.body,{childList:true,subtree:true});
                setInterval(addImportButton,1200);
              }
              function addImportButton(){
                let rows=[];
                for(const table of document.querySelectorAll('table')){
                  const trs=Array.from(table.querySelectorAll('tr'));
                  if(trs.length>1) rows=trs;
                }
                if(!rows.length) return;
                let button=document.getElementById('mondo-import-results');
                if(!button){
                  button=document.createElement('button');button.id='mondo-import-results';
                  button.textContent='IMPORT RESULTS INTO MONDO';
                  button.style='position:fixed;z-index:2147483647;bottom:18px;left:5%;width:90%;padding:17px;background:#ff8a24;color:#111;border:0;border-radius:12px;font-weight:900;font-size:16px;box-shadow:0 4px 18px #0008';
                  button.onclick=function(){
                    const tables=Array.from(document.querySelectorAll('table'));
                    let chosen=null;
                    for(const table of tables){const tr=Array.from(table.querySelectorAll('tr'));if(tr.length>1)chosen=tr;}
                    if(!chosen){alert('No analysed track table found yet.');return;}
                    const headers=Array.from(chosen[0].querySelectorAll('th,td')).map(x=>(x.innerText||x.textContent||'').trim().toLowerCase());
                    const find=(cells,terms)=>{let i=headers.findIndex(h=>terms.some(t=>h.includes(t)));return i>=0?(cells[i]||'').trim():'';};
                    const out=chosen.slice(1).map(tr=>{
                      const cells=Array.from(tr.querySelectorAll('td,th')).map(x=>(x.innerText||x.textContent||'').trim());
                      const joined=cells.join(' ');
                      const bpm=find(cells,['bpm','tempo']);
                      const camel=find(cells,['camelot']);
                      const key=find(cells,['key','tonality']);
                      const title=find(cells,['track','song','title','name']) || cells[0] || '';
                      const artist=find(cells,['artist']);
                      return {title:title,artist:artist,bpm:parseFloat(bpm.replace(/[^0-9.]/g,''))||null,key:camel||key};
                    }).filter(x=>x.title);
                    if(!out.length){alert('TuneBad results appeared, but MONDO could not read the track rows.');return;}
                    MondoBridge.importResults(JSON.stringify(out));
                  };
                  document.body.appendChild(button);
                }
              }
              setTimeout(start,900);
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    @Deprecated("Deprecated for compatibility")
    override fun onBackPressed() {
        if (tuneBadMode) {
            tuneBadMode = false
            web.loadUrl("file:///android_asset/index.html")
        } else if (web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
