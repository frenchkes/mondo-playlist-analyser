package com.mondo.dj

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import android.text.InputType
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

data class TrackRow(val title:String,val artist:String,var bpm:String="",var camelot:String="",var energy:String="")

class MainActivity : Activity() {
    private val orange = Color.rgb(255,138,36)
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val tracks = mutableListOf<TrackRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(20,22,25)
        window.navigationBarColor = Color.rgb(20,22,25)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(17,19,21)); setPadding(20,24,20,20) }
        val title = TextView(this).apply { text="MONDO."; textSize=30f; setTextColor(orange); gravity=Gravity.CENTER_VERTICAL; setPadding(0,0,0,8); typeface=android.graphics.Typeface.DEFAULT_BOLD }
        root.addView(title)
        root.addView(TextView(this).apply { text="PLAYLIST ANALYSER  •  DJ SET BUILDER"; textSize=11f; setTextColor(Color.LTGRAY) })
        input = EditText(this).apply { hint="Paste Spotify playlist link"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); inputType=InputType.TYPE_TEXT_VARIATION_URI; setSingleLine(true); setPadding(12,12,12,12) }
        root.addView(input, LinearLayout.LayoutParams(-1,-2).apply { topMargin=24 })
        val go = Button(this).apply { text="ANALYSE PLAYLIST"; setTextColor(Color.BLACK); setBackgroundColor(orange); setOnClickListener { analyse() } }
        root.addView(go, LinearLayout.LayoutParams(-1,-2).apply { topMargin=10 })
        status = TextView(this).apply { text="Paste a public Spotify playlist link to begin."; setTextColor(Color.LTGRAY); textSize=13f; setPadding(0,16,0,12) }
        root.addView(status)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun analyse() {
        val id = Regex("(?:playlist/|spotify:playlist:)([A-Za-z0-9]+)").find(input.text.toString())?.groupValues?.get(1)
        if (id == null) { status.text="That doesn't look like a Spotify playlist link."; return }
        status.text="Reading playlist…"
        tracks.clear(); list.removeAllViews()
        thread {
            try {
                val html = get("https://open.spotify.com/embed/playlist/$id")
                val marker = Regex("<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
                val found = mutableListOf<Pair<String,String>>()
                if (marker != null) {
                    val json = JSONObject(marker)
                    collectTracks(json, found)
                }
                if (found.isEmpty()) throw Exception("Spotify did not return a readable public track list. Check that the playlist is public.")
                runOnUiThread { status.text="Found ${found.size} tracks. Looking up BPM, key and energy…" }
                found.distinct().forEachIndexed { index, pair ->
                    val row = TrackRow(pair.first,pair.second)
                    try {
                        val q=URLEncoder.encode("${pair.first} ${pair.second}","UTF-8")
                        val search=JSONObject(get("https://songfinder.dev/api/track/search?q=$q",true))
                        val candidates=search.optJSONArray("data") ?: JSONArray()
                        if(candidates.length()>0) {
                            val candidate=candidates.getJSONObject(0)
                            val isrc=candidate.optString("isrc")
                            if(isrc.isNotBlank()) {
                                val detail=JSONObject(get("https://songfinder.dev/api/track/detail?isrc=$isrc",true)).optJSONObject("data")
                                val features=detail?.optJSONObject("features")
                                row.bpm=features?.opt("tempo")?.toString()?.takeIf{it!="null"}?.let{String.format("%.1f",it.toDouble())} ?: ""
                                row.camelot=features?.optJSONObject("key")?.optString("camelot","") ?: ""
                                row.energy=features?.opt("energy")?.toString()?.takeIf{it!="null"}?.let{String.format("%.0f",it.toDouble()*10)} ?: ""
                            }
                        }
                    } catch (_:Exception) { }
                    tracks.add(row)
                    runOnUiThread { addRow(row,index+1); status.text="Analysed metadata for $index of ${found.size} tracks…" }
                    if(index<found.lastIndex) Thread.sleep(1200)
                }
                runOnUiThread { status.text="Finished. ${tracks.size} tracks loaded. Missing values mean no reliable catalogue match was found." }
            } catch(e:Exception) { runOnUiThread { status.text="Couldn't load playlist: ${e.message}" } }
        }
    }

    private fun collectTracks(value:Any?, out:MutableList<Pair<String,String>>) {
        when(value) {
            is JSONObject -> {
                val uri=value.optString("uri","")
                val name=value.optString("name","")
                val artists=value.optJSONArray("artists")
                if(uri.startsWith("spotify:track:") && name.isNotBlank() && artists!=null) {
                    val artist=(0 until artists.length()).mapNotNull{artists.optJSONObject(it)?.optString("name")}.joinToString(", ")
                    if(artist.isNotBlank()) out.add(name to artist)
                }
                val keys=value.keys()
                while(keys.hasNext()) collectTracks(value.opt(keys.next()),out)
            }
            is JSONArray -> for(i in 0 until value.length()) collectTracks(value.opt(i),out)
        }
    }

    private fun get(address:String, songFinder:Boolean=false):String {
        val c=URL(address).openConnection() as HttpURLConnection
        c.connectTimeout=20000; c.readTimeout=30000; c.setRequestProperty("User-Agent","Mozilla/5.0 MONDO Android")
        c.setRequestProperty("Accept","application/json,text/html,*/*")
        if(songFinder) c.setRequestProperty("X-SongFinder-Client","cli")
        try { val code=c.responseCode; val stream=if(code in 200..299)c.inputStream else c.errorStream
            val body=stream.bufferedReader().use{it.readText()}
            if(code !in 200..299) throw Exception("HTTP $code: $body")
            return body
        } finally { c.disconnect() }
    }

    private fun addRow(t:TrackRow,n:Int) {
        val line=TextView(this).apply {
            text="$n. ${t.title} — ${t.artist}\n   BPM: ${t.bpm.ifBlank{"—"}}    Camelot: ${t.camelot.ifBlank{"—"}}    Energy: ${t.energy.ifBlank{"—"}}/10"
            textSize=13f; setTextColor(Color.WHITE); setPadding(10,12,10,12); setBackgroundColor(if(n%2==0) Color.rgb(30,33,37) else Color.rgb(23,26,29))
        }
        list.addView(line,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=5})
    }
}
