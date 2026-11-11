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
import android.net.Uri
import android.content.SharedPreferences
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.io.OutputStreamWriter
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var tuneBadMode = false
    private var pendingPlaylist = ""
    private val spotifyRedirect = "https://glittering-kulfi-e8083b.netlify.app/callback"
    private val spotifyPrefs by lazy { getSharedPreferences("mondo_spotify", MODE_PRIVATE) }
    private val appPrefs by lazy { getSharedPreferences("mondo_app", MODE_PRIVATE) }

    inner class MondoBridge {
        @JavascriptInterface
        fun getSavedSetsJson(): String {
            return appPrefs.getString("saved_sets", "{}") ?: "{}"
        }

        @JavascriptInterface
        fun saveSavedSetsJson(json: String) {
            try {
                JSONObject(json)
                appPrefs.edit().putString("saved_sets", json).apply()
            } catch (_: Exception) {
                // Preserve the last valid saved-set backup.
            }
        }

        @JavascriptInterface
        fun getThemePreference(): String {
            return appPrefs.getString("theme", "dark") ?: "dark"
        }

        @JavascriptInterface
        fun setThemePreference(theme: String) {
            if (theme == "light" || theme == "dark") {
                appPrefs.edit().putString("theme", theme).apply()
            }
        }

        @JavascriptInterface
        fun openSpotifyPlaylist(url: String) {
            runOnUiThread {
                val uri = Uri.parse(url)
                try {
                    val spotifyIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.spotify.music")
                    startActivity(spotifyIntent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(this@MainActivity, "Could not open Spotify. Please check that Spotify is installed.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        @JavascriptInterface
        fun randomSpotifyString(length: Int): String {
            val bytes = ByteArray(length.coerceIn(16, 64))
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        @JavascriptInterface
        fun createSpotifyChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
        @JavascriptInterface
        fun openTuneBad(playlistUrl: String) {
            runOnUiThread {
                pendingPlaylist = playlistUrl
                tuneBadMode = false
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tunebad.com/playlist-analyzer"))
                    startActivity(intent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "Could not open your browser. Please open TuneBad manually.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun isSpotifyConnected(): Boolean {
            return !spotifyPrefs.getString("access_token", null).isNullOrBlank() &&
                !spotifyPrefs.getString("refresh_token", null).isNullOrBlank()
        }

        @JavascriptInterface
        fun startSpotifyAuth(authUrl: String, verifier: String, state: String) {
            spotifyPrefs.edit().putString("verifier", verifier).putString("state", state).apply()
            runOnUiThread { web.loadUrl(authUrl) }
        }

        @JavascriptInterface
        fun createSpotifyPlaylist(name: String, tracksJson: String) {
            Thread {
                try {
                    val token = getFreshSpotifyAccessToken()
                    val tracks = JSONArray(tracksJson)
                    val uris = JSONArray()
                    val missing = mutableListOf<String>()
                    for (i in 0 until tracks.length()) {
                        val track = tracks.getJSONObject(i)
                        val title = track.optString("title").trim()
                        val artist = track.optString("artist").trim()
                        val originalUri = track.optString("spotifyUri").trim()
                        if (originalUri.matches(Regex("^spotify:track:[A-Za-z0-9]{22}$"))) {
                            // Prefer the exact recording from the user's Spotify export.
                            uris.put(originalUri)
                            continue
                        }
                        val q = "track:" + title + if (artist.isNotBlank()) " artist:" + artist else ""
                        val searchUrl = "https://api.spotify.com/v1/search?type=track&limit=10&q=" +
                            URLEncoder.encode(q, "UTF-8")
                        val search = spotifyRequest(searchUrl, "GET", token, null)
                        val items = search.optJSONObject("tracks")?.optJSONArray("items")
                        val wantedTitle = normaliseMatchText(title)
                        val wantedArtist = normaliseMatchText(artist)
                        var matched: JSONObject? = null
                        if (items != null) {
                            for (j in 0 until items.length()) {
                                val candidate = items.getJSONObject(j)
                                val candidateTitle = normaliseMatchText(candidate.optString("name"))
                                val candidateArtists = candidate.optJSONArray("artists")
                                var artistMatches = artist.isBlank()
                                if (candidateArtists != null) {
                                    for (k in 0 until candidateArtists.length()) {
                                        if (normaliseMatchText(candidateArtists.getJSONObject(k).optString("name")) == wantedArtist) {
                                            artistMatches = true
                                            break
                                        }
                                    }
                                }
                                if (candidateTitle == wantedTitle && artistMatches) {
                                    matched = candidate
                                    break
                                }
                            }
                        }
                        if (matched != null) {
                            uris.put(matched.getString("uri"))
                        } else {
                            missing.add(title + " - " + artist)
                        }
                    }
                    if (uris.length() == 0) throw Exception("No tracks could be matched to Spotify.")
                    val body = JSONObject().put("name", name).put("public", false)
                        .put("description", "Created by MONDO Playlist Analyser")
                    val created = spotifyRequest("https://api.spotify.com/v1/me/playlists", "POST", token, body)
                    val playlistId = created.getString("id")
                    var offset = 0
                    while (offset < uris.length()) {
                        val batch = JSONArray()
                        for (j in offset until minOf(offset + 100, uris.length())) batch.put(uris.getString(j))
                        spotifyRequest("https://api.spotify.com/v1/playlists/$playlistId/items", "POST", token,
                            JSONObject().put("uris", batch))
                        offset += 100
                    }
                    val url = created.optJSONObject("external_urls")?.optString("spotify", "") ?: ""
                    val message = if (missing.isEmpty()) "Playlist created with " + uris.length() + " tracks." else
                        "Playlist created with " + uris.length() + " matched tracks. " + missing.size + " could not be found."
                    runOnUiThread {
                        val safeUrl = JSONObject.quote(url)
                        val safeMessage = JSONObject.quote(message)
                        web.evaluateJavascript("window.onSpotifyPlaylistCreated($safeUrl,$safeMessage);", null)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        val safe = JSONObject.quote(e.message ?: "Unknown Spotify error")
                        web.evaluateJavascript("window.onSpotifyPlaylistError($safe);", null)
                    }
                }
            }.start()
        }

        @Synchronized
        private fun getFreshSpotifyAccessToken(): String {
            val accessToken = spotifyPrefs.getString("access_token", null)
                ?: throw Exception("Please connect to Spotify first.")
            val refreshToken = spotifyPrefs.getString("refresh_token", null)
                ?: throw Exception("Spotify needs reconnecting once. Tap CONNECT TO SPOTIFY and authorise MONDO again.")
            val expiresAt = spotifyPrefs.getLong("expires_at", 0L)
            if (System.currentTimeMillis() < expiresAt - 60_000L) return accessToken

            val connection = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            val params = "client_id=a2d83e1dfcc74db5a310f73192ed01d2" +
                "&grant_type=refresh_token" +
                "&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
            connection.outputStream.use { it.write(params.toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            connection.disconnect()
            if (responseCode !in 200..299) {
                throw Exception("Spotify session expired. Tap CONNECT TO SPOTIFY and authorise MONDO again.")
            }
            val response = JSONObject(responseText)
            val newAccessToken = response.getString("access_token")
            val expiresIn = response.optLong("expires_in", 3600L)
            val editor = spotifyPrefs.edit()
                .putString("access_token", newAccessToken)
                .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000L)
            val rotatedRefreshToken = response.optString("refresh_token", "")
            if (rotatedRefreshToken.isNotBlank()) editor.putString("refresh_token", rotatedRefreshToken)
            editor.apply()
            return newAccessToken
        }

        private fun normaliseMatchText(value: String): String {
            val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            return decomposed.replace("\\p{Mn}+".toRegex(), "")
                .lowercase(Locale.ROOT)
                .replace("[^a-z0-9]".toRegex(), "")
        }

        private fun spotifyRequest(url: String, method: String, token: String, body: JSONObject?): JSONObject {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            connection.disconnect()
            if (code !in 200..299) throw Exception("Spotify API error $code: $text")
            return JSONObject(text)
        }

        fun exchangeSpotifyCode(code: String, state: String) {
            Thread {
                try {
                    if (state != spotifyPrefs.getString("state", null)) throw Exception("Security check failed. Please reconnect.")
                    val verifier = spotifyPrefs.getString("verifier", null) ?: throw Exception("Missing PKCE verifier. Please reconnect.")
                    val connection = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val params = "client_id=a2d83e1dfcc74db5a310f73192ed01d2&grant_type=authorization_code" +
                        "&code=" + URLEncoder.encode(code, "UTF-8") +
                        "&redirect_uri=" + URLEncoder.encode(spotifyRedirect, "UTF-8") +
                        "&code_verifier=" + URLEncoder.encode(verifier, "UTF-8")
                    connection.outputStream.use { it.write(params.toByteArray(Charsets.UTF_8)) }
                    val responseCode = connection.responseCode
                    val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    val text = stream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    if (responseCode !in 200..299) throw Exception("Token exchange failed ($responseCode): $text")
                    val tokenResponse = JSONObject(text)
                    val token = tokenResponse.getString("access_token")
                    val refreshToken = tokenResponse.optString("refresh_token", "")
                    if (refreshToken.isBlank()) throw Exception("Spotify did not provide a refresh token. Please try connecting again.")
                    val expiresIn = tokenResponse.optLong("expires_in", 3600L)
                    spotifyPrefs.edit()
                        .putString("access_token", token)
                        .putString("refresh_token", refreshToken)
                        .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000L)
                        .remove("verifier").remove("state").apply()
                    runOnUiThread {
                        web.loadUrl("file:///android_asset/index.html")
                        web.postDelayed({ web.evaluateJavascript("window.onSpotifyConnected && window.onSpotifyConnected();", null) }, 700)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        web.loadUrl("file:///android_asset/index.html")
                        web.postDelayed({
                            val safe = JSONObject.quote(e.message ?: "Spotify authorisation failed")
                            web.evaluateJavascript("window.onSpotifyAuthError($safe);", null)
                        }, 700)
                    }
                }
            }.start()
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
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && url.startsWith(spotifyRedirect)) {
                    val uri = Uri.parse(url)
                    val code = uri.getQueryParameter("code")
                    val state = uri.getQueryParameter("state") ?: ""
                    val error = uri.getQueryParameter("error")
                    if (code != null) MondoBridge().exchangeSpotifyCode(code, state)
                    else {
                        web.loadUrl("file:///android_asset/index.html")
                        web.postDelayed({
                            val safe = JSONObject.quote(error ?: "Spotify login cancelled")
                            web.evaluateJavascript("window.onSpotifyAuthError($safe);", null)
                        }, 700)
                    }
                    return true
                }
                return false
            }
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
              const norm=s=>(s||'').toString().toLowerCase();
              const label=e=>norm((e.innerText||e.textContent||'').trim());
              const overlay=(message)=>{
                let n=document.getElementById('mondo-tunebad-note');
                if(!n){n=document.createElement('div');n.id='mondo-tunebad-note';n.style='position:fixed;z-index:2147483647;bottom:12px;left:4%;width:92%;padding:13px;background:#ff8a24;color:#17191c;border-radius:10px;font-weight:800;box-shadow:0 4px 18px #0008';document.body.appendChild(n);}
                n.textContent='MONDO: '+message;
              };
              function findInput(){
                const inputs=Array.from(document.querySelectorAll('input:not([type=file]),textarea'));
                return inputs.find(el=>/spotify|youtube|playlist|link/i.test((el.placeholder||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.name||'')))
                  || inputs.find(el=>el.type==='text'||el.tagName==='TEXTAREA');
              }
              function findSubmit(){
                const buttons=Array.from(document.querySelectorAll('button,[role=button],input[type=submit]'));
                return buttons.find(b=>/analy[sz]e playlist|analy[sz]e/i.test(label(b))&&!b.disabled)
                  || buttons.find(b=>/analy[sz]e playlist|analy[sz]e/i.test(label(b)));
              }
              let submitted=false;
              function fillForm(){
                if(submitted)return;
                const input=findInput();
                if(!input)return;
                if(input.value!==playlistUrl){
                  const proto=input.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                  const setter=Object.getOwnPropertyDescriptor(proto,'value')?.set;
                  if(setter)setter.call(input,playlistUrl);else input.value=playlistUrl;
                  input.dispatchEvent(new Event('input',{bubbles:true}));
                  input.dispatchEvent(new Event('change',{bubbles:true}));
                  input.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'v'}));
                }
                const button=findSubmit();
                if(button&&!button.disabled&&input.value===playlistUrl){
                  submitted=true;
                  button.click();
                  overlay('Playlist submitted to TuneBad. Waiting for analysis results…');
                  startResultWatcher();
                }
              }
              function readResults(){
                const tables=Array.from(document.querySelectorAll('table'));
                for(const table of tables){
                  const rows=Array.from(table.querySelectorAll('tr'));
                  if(rows.length<2)continue;
                  const heads=Array.from(rows[0].querySelectorAll('th,td')).map(c=>label(c));
                  const idx=(terms)=>heads.findIndex(h=>terms.some(t=>h===t||h.includes(t)));
                  const ti=idx(['track','song','title','name']);
                  const bi=idx(['bpm','tempo']);
                  const ki=idx(['camelot','key','tonality']);
                  if(ti<0||bi<0||ki<0)continue;
                  const ai=idx(['artist','artists']);
                  const out=rows.slice(1).map(tr=>{
                    const cells=Array.from(tr.querySelectorAll('td,th')).map(c=>(c.innerText||c.textContent||'').trim());
                    const get=i=>i>=0?(cells[i]||''):'';
                    const camelot= get(idx(['camelot'])) || get(ki);
                    return {title:get(ti),artist:get(ai),bpm:parseFloat(get(bi).replace(/[^0-9.]/g,''))||null,key:camelot,camelot:camelot};
                  }).filter(x=>x.title&&x.bpm);
                  if(out.length)return out;
                }
                return null;
              }
              let importAdded=false;
              function startResultWatcher(){
                const add=()=>{
                  const results=readResults();
                  if(!results)return;
                  let button=document.getElementById('mondo-import-results');
                  if(!button){
                    button=document.createElement('button');button.id='mondo-import-results';
                    button.textContent='IMPORT RESULTS INTO MONDO';
                    button.style='position:fixed;z-index:2147483647;bottom:18px;left:5%;width:90%;padding:17px;background:#ff8a24;color:#111;border:0;border-radius:12px;font-weight:900;font-size:16px;box-shadow:0 4px 18px #0008';
                    button.onclick=()=>{const latest=readResults();if(!latest||!latest.length){overlay('No analysed rows found yet. Wait for TuneBad to finish.');return;}MondoBridge.importResults(JSON.stringify(latest));};
                    document.body.appendChild(button);
                  }
                  button.textContent='IMPORT '+results.length+' TRACKS INTO MONDO';
                  importAdded=true;
                  overlay('Analysis found. Tap the orange button to bring the results into MONDO.');
                };
                const observer=new MutationObserver(add);
                observer.observe(document.documentElement,{childList:true,subtree:true,characterData:true});
                add();
              }
              const observer=new MutationObserver(()=>{fillForm();});
              observer.observe(document.documentElement,{childList:true,subtree:true});
              let attempts=0;
              const poll=setInterval(()=>{attempts++;fillForm();if(submitted||attempts>240){clearInterval(poll);if(!submitted)overlay('Could not find the playlist form. Paste the playlist link into TuneBad manually, then run the analysis.');}},500);
              window.addEventListener('load',fillForm);
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
