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
              const playlistUrl=$playlist;
              const log=(msg)=>{try{console.log('[MONDO] '+msg)}catch(e){}};
              function findInput(){
                return Array.from(document.querySelectorAll('input')).find(el =>
                  /spotify|youtube|playlist|link/i.test(
                    (el.placeholder||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.name||'')
                  )
                ) || Array.from(document.querySelectorAll('input')).find(el=>el.type==='text' || !el.type);
              }
              function fillAndSubmit(){
                const input=findInput();
                const button=Array.from(document.querySelectorAll('button')).find(b =>
                  /analyze playlist/i.test((b.innerText||b.textContent||'').trim())
                );
                if(!input || !button){log('Waiting for TuneBad form');return false;}
                // React-controlled inputs must be changed through the native setter.
                const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
                setter.call(input,playlistUrl);
                input.dispatchEvent(new Event('input',{bubbles:true}));
                input.dispatchEvent(new Event('change',{bubbles:true}));
                input.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'v'}));
                log('Filled playlist input; waiting for validation');
                let attempts=0;
                const poll=setInterval(()=>{
                  attempts++;
                  const current=findInput();
                  const submit=Array.from(document.querySelectorAll('button')).find(b =>
                    /analyze playlist/i.test((b.innerText||b.textContent||'').trim())
                  );
                  if(current && current.value===playlistUrl && submit && !submit.disabled){
                    clearInterval(poll);
                    submit.click();
                    log('Submitted TuneBad playlist');
                    watchResults();
                  } else if(attempts>30){
                    clearInterval(poll);
                    log('TuneBad did not enable Analyze Playlist; check link and page validation');
                    const note=document.createElement('div');
                    note.textContent='MONDO: The playlist link was filled, but TuneBad did not enable Analyze Playlist. Check that the playlist is public and the full link is valid.';
                    note.style='position:fixed;z-index:2147483647;bottom:12px;left:4%;width:92%;padding:14px;background:#ff8a24;color:#111;border-radius:10px;font-weight:700';
                    document.body.appendChild(note);
                  }
                },300);
                return true;
              }
              function watchResults(){
                const observer=new MutationObserver(()=>addImportButton());
                observer.observe(document.body,{childList:true,subtree:true});
                setInterval(addImportButton,1000);
                addImportButton();
              }
              function addImportButton(){
                const tables=Array.from(document.querySelectorAll('table'));
                let selected=null;
                for(const table of tables){const trs=Array.from(table.querySelectorAll('tr'));if(trs.length>1)selected=trs;}
                const rows=selected;
                if(!rows)return;
                let button=document.getElementById('mondo-import-results');
                if(!button){
                  button=document.createElement('button');button.id='mondo-import-results';
                  button.textContent='IMPORT RESULTS INTO MONDO';
                  button.style='position:fixed;z-index:2147483647;bottom:18px;left:5%;width:90%;padding:17px;background:#ff8a24;color:#111;border:0;border-radius:12px;font-weight:900;font-size:16px;box-shadow:0 4px 18px #0008';
                  button.onclick=function(){
                    const heads=Array.from(rows[0].querySelectorAll('th,td')).map(x=>(x.innerText||x.textContent||'').trim().toLowerCase());
                    const idx=(terms)=>heads.findIndex(h=>terms.some(t=>h.includes(t)));
                    const out=rows.slice(1).map(tr=>{
                      const cells=Array.from(tr.querySelectorAll('td,th')).map(x=>(x.innerText||x.textContent||'').trim());
                      const val=(terms)=>{const i=idx(terms);return i>=0?(cells[i]||''):'';};
                      return {title:val(['track','song','title','name'])||cells[0]||'',artist:val(['artist']),bpm:parseFloat(val(['bpm','tempo']).replace(/[^0-9.]/g,''))||null,key:val(['camelot'])||val(['key','tonality'])};
                    }).filter(x=>x.title);
                    if(!out.length){alert('No readable analysed tracks found yet.');return;}
                    MondoBridge.importResults(JSON.stringify(out));
                  };
                  document.body.appendChild(button);
                }
              }
              let tries=0;
              const starter=setInterval(()=>{tries++;if(fillAndSubmit()||tries>20)clearInterval(starter);},500);
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
