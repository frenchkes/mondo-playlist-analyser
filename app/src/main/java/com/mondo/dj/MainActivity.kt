package com.mondo.dj

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import android.text.InputType
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

data class TrackRow(val title:String,val artist:String,var bpm:Double?=null,var camelot:String="",var energy:Double?=null)

class MainActivity : Activity() {
    private val orange = Color.rgb(255,138,36)
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var sortButton: Button
    private val tracks = mutableListOf<TrackRow>()
    private var sortMode = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(20,22,25)
        window.navigationBarColor = Color.rgb(20,22,25)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(17,19,21)); setPadding(18,20,18,12) }
        root.addView(TextView(this).apply { text="MONDO."; textSize=32f; setTextColor(orange); typeface=android.graphics.Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text="SPOTIFY PLAYLIST • DJ SET BUILDER"; textSize=11f; setTextColor(Color.LTGRAY) })
        input = EditText(this).apply { hint="Paste Spotify playlist link"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); inputType=InputType.TYPE_TEXT_VARIATION_URI; setSingleLine(true); setPadding(12,12,12,12) }
        root.addView(input, LinearLayout.LayoutParams(-1,-2).apply { topMargin=20 })
        val go = Button(this).apply { text="ANALYSE PLAYLIST"; setTextColor(Color.BLACK); setBackgroundColor(orange); setOnClickListener { analyse() } }
        root.addView(go, LinearLayout.LayoutParams(-1,-2).apply { topMargin=8 })
        val tools = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        sortButton = Button(this).apply { text="SORT: PLAYLIST ORDER"; setOnClickListener { sortTracks() } }
        tools.addView(sortButton, LinearLayout.LayoutParams(0,-2,1f))
        val export = Button(this).apply { text="EXPORT CSV"; setOnClickListener { exportCsv() } }
        tools.addView(export)
        root.addView(tools)
        status = TextView(this).apply { text="Paste a public Spotify playlist link to begin."; setTextColor(Color.LTGRAY); textSize=13f; setPadding(0,8,0,8) }
        root.addView(status)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun analyse() {
        if (busy) return
        val id = Regex("(?:playlist/|spotify:playlist:)([A-Za-z0-9]+)").find(input.text.toString())?.groupValues?.get(1)
        if (id == null) { status.text="Please paste a valid Spotify playlist link."; return }
        busy=true; tracks.clear(); list.removeAllViews(); status.text="Reading Spotify playlist…"
        thread {
            try {
                val html = get("https://open.spotify.com/embed/playlist/$id")
                val found = mutableListOf<Pair<String,String>>()
                val scripts = listOf(
                    Regex("<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL),
                    Regex("<script[^>]*type=[\"']application/json[\"'][^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                )
                for (rx in scripts) {
                    val match=rx.find(html)?.groupValues?.get(1) ?: continue
                    try { collectTracks(JSONObject(match),found) } catch (_:Exception) {}
                    if(found.isNotEmpty()) break
                }
                if(found.isEmpty()) {
                    val decoded=html.replace("\\u0026","&").replace("\\u003c","<")
                    val trackRx=Regex("\"uri\"\\s*:\\s*\"spotify:track:[^\"]+\".{0,1200}?\"name\"\\s*:\\s*\"([^\"]+)\"")
                    if(trackRx.containsMatchIn(decoded)) throw Exception("Spotify changed its embed data format. This public playlist could not be read.")
                    throw Exception("No tracks found. Make sure the playlist is public and try again.")
                }
                val unique=found.distinct()
                runOnUiThread { status.text="Found ${unique.size} tracks. Fetching DJ data…" }
                unique.forEachIndexed { index, pair ->
                    val row=TrackRow(pair.first,pair.second)
                    try {
                        val q=URLEncoder.encode("${pair.first} ${pair.second}","UTF-8")
                        val search=JSONObject(get("https://songfinder.dev/api/track/search?q=$q",true))
                        val candidates=search.optJSONArray("data") ?: search.optJSONArray("tracks") ?: JSONArray()
                        if(candidates.length()>0) {
                            val candidate=candidates.getJSONObject(0)
                            val isrc=candidate.optString("isrc","")
                            if(isrc.isNotBlank()) {
                                val detail=JSONObject(get("https://songfinder.dev/api/track/detail?isrc=${URLEncoder.encode(isrc,"UTF-8")}",true))
                                val data=detail.optJSONObject("data") ?: detail
                                val features=data.optJSONObject("features") ?: data
                                row.bpm=features.optDouble("tempo").takeIf{it>0}
                                val key=features.optJSONObject("key")
                                row.camelot=key?.optString("camelot","") ?: features.optString("camelot","")
                                row.energy=features.optDouble("energy").takeIf{it>0}
                            }
                        }
                    } catch (_:Exception) {}
                    synchronized(tracks) { tracks.add(row) }
                    runOnUiThread { render(); status.text="Looking up ${index+1} of ${unique.size} tracks…" }
                    if(index<unique.lastIndex) Thread.sleep(1100)
                }
                runOnUiThread { status.text="Finished: ${unique.size} tracks. Blank values mean no catalogue match was found."; busy=false }
            } catch(e:Exception) {
                runOnUiThread { status.text="Couldn't read playlist: ${e.message}"; busy=false }
            }
        }
    }

    private fun collectTracks(value:Any?, out:MutableList<Pair<String,String>>) {
        when(value) {
            is JSONObject -> {
                val uri=value.optString("uri","")
                val name=value.optString("name","")
                val artists=value.optJSONArray("artists")
                val artistArray=if(artists!=null) (0 until artists.length()).mapNotNull {
                    val a=artists.opt(it)
                    when(a) {
                        is JSONObject -> a.optString("name","").takeIf{it.isNotBlank()}
                        is String -> a.takeIf{it.isNotBlank()}
                        else -> null
                    }
                }.joinToString(", ") else ""
                val artist=artistArray.ifBlank {
                    value.optString("subtitle","").ifBlank {
                        value.optString("artistName","").ifBlank { value.optString("artists","") }
                    }
                }
                if(uri.startsWith("spotify:track:") && name.isNotBlank() && artist.isNotBlank()) {
                    out.add(name to artist)
                }
                val keys=value.keys(); while(keys.hasNext()) collectTracks(value.opt(keys.next()),out)
            }
            is JSONArray -> for(i in 0 until value.length()) collectTracks(value.opt(i),out)
        }
    }

    private fun get(address:String, songFinder:Boolean=false):String {
        val c=URL(address).openConnection() as HttpURLConnection
        c.connectTimeout=20000; c.readTimeout=30000
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        c.setRequestProperty("Accept","application/json,text/html,*/*")
        if(songFinder) c.setRequestProperty("X-SongFinder-Client","cli")
        try {
            val code=c.responseCode
            val stream=if(code in 200..299)c.inputStream else c.errorStream
            val body=stream.bufferedReader().use{it.readText()}
            if(code !in 200..299) throw Exception("Server returned HTTP $code")
            return body
        } finally { c.disconnect() }
    }

    private fun sortTracks() {
        if(tracks.isEmpty()) return
        sortMode=(sortMode+1)%3
        when(sortMode) {
            0 -> { sortButton.text="SORT: PLAYLIST ORDER"; status.text="Original Spotify order" }
            1 -> { tracks.sortWith(compareBy<TrackRow>{it.bpm ?: Double.MAX_VALUE}); sortButton.text="SORT: BPM LOW → HIGH"; status.text="Sorted by BPM" }
            else -> { tracks.sortWith(compareBy<TrackRow>{it.camelot.ifBlank{"ZZ"}}); sortButton.text="SORT: CAMELOT"; status.text="Sorted by Camelot key" }
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        tracks.forEachIndexed { i,t ->
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(12,10,12,10); setBackgroundColor(if(i%2==0) Color.rgb(30,33,37) else Color.rgb(23,26,29)) }
            card.addView(TextView(this).apply { text="${i+1}. ${t.title}"; textSize=14f; setTextColor(Color.WHITE); typeface=android.graphics.Typeface.DEFAULT_BOLD })
            card.addView(TextView(this).apply {
                val bpm=t.bpm?.let{String.format(java.util.Locale.US,"%.1f",it)} ?: "—"
                val energy=t.energy?.let{String.format(java.util.Locale.US,"%.1f",it*10)} ?: "—"
                text="${t.artist}\nBPM: $bpm     Camelot: ${t.camelot.ifBlank{"—"}}     Energy: $energy"
                textSize=12f; setTextColor(Color.LTGRAY); setPadding(0,5,0,0)
            })
            list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=5})
        }
    }

    private fun exportCsv() {
        if(tracks.isEmpty()){status.text="Analyse a playlist first.";return}
        val csv=buildString {
            append("Title,Artist,BPM,Camelot,Energy\n")
            tracks.forEach { t ->
                fun q(s:String)="\"" + s.replace(",", " ") + "\""
                append("${q(t.title)},${q(t.artist)},${t.bpm ?: ""},${q(t.camelot)},${t.energy ?: ""}\n")
            }
        }
        val intent=android.content.Intent(android.content.Intent.ACTION_SEND).apply { type="text/csv"; putExtra(android.content.Intent.EXTRA_TEXT,csv); putExtra(android.content.Intent.EXTRA_SUBJECT,"MONDO playlist analysis CSV") }
        startActivity(android.content.Intent.createChooser(intent,"Share playlist CSV"))
    }
}
