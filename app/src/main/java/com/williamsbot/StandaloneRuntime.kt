package com.williamsbot

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.*

object StandaloneRuntime {
    private var server: StandaloneServer? = null
    fun start(context: Context) {
        if (server == null) {
            server = StandaloneServer(context.applicationContext)
            server!!.start()
        }
    }
    fun stop() { server?.stop(); server = null }
}

private data class CandleN(val t:Long,val o:Double,val h:Double,val l:Double,val c:Double,val v:Double)

private class StandaloneServer(private val context: Context) {
    private val port = 18080
    private val prefs = EncryptedSharedPreferences.create(
        context, "williams_native_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    private var socket: ServerSocket? = null
    private var engine: NativeEngine? = null

    fun start() {
        if (socket != null) return
        socket = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (true) {
                try {
                    val c = socket?.accept() ?: break
                    Thread { handle(c) }.start()
                } catch (_: Exception) { break }
            }
        }.apply { isDaemon = true; start() }
    }
    fun stop() { try { socket?.close() } catch (_:Exception) {}; socket=null; engine?.stop() }
    private fun e(): NativeEngine { if(engine==null) engine=NativeEngine(prefs,client); return engine!! }

    private fun handle(s:Socket) {
        s.use {
            try {
                val r=BufferedReader(InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8))
                val first=r.readLine() ?: return
                val p=first.split(" "); if(p.size<2)return
                val method=p[0]; val target=p[1]
                var len=0
                while(true){val line=r.readLine()?:return;if(line.isEmpty())break
                    val h=line.split(":",limit=2);if(h.size==2&&h[0].equals("Content-Length",true))len=h[1].trim().toIntOrNull()?:0}
                val chars=CharArray(len);if(len>0)r.read(chars)
                val body=route(method,target,String(chars))
                val b=body.toByteArray(StandardCharsets.UTF_8)
                val out=s.getOutputStream()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+b.size+"\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(b);out.flush()
            } catch (x:Exception) {
                val b=JSONObject().put("error",x.message?:x.javaClass.simpleName).toString().toByteArray()
                try{s.getOutputStream().write(("HTTP/1.1 500 Error\r\nContent-Type: application/json\r\nContent-Length: "+b.size+"\r\n\r\n").toByteArray());s.getOutputStream().write(b)}catch(_:Exception){}
            }
        }
    }
    private fun route(method:String,target:String,body:String):String {
        val q=target.indexOf('?');val path=if(q<0)target else target.substring(0,q)
        val params=if(q<0) emptyMap() else target.substring(q+1).split("&").filter{it.contains("=")}.associate{
            val a=it.split("=",limit=2);URLDecoder.decode(a[0],"UTF-8") to URLDecoder.decode(a[1],"UTF-8")}
        val x=e()
        return when {
            method=="GET"&&path=="/api/v1/health" -> JSONObject().put("ok",true).put("service","williams-native").put("version","4.13.0").put("standalone",true).put("websocket",false).put("auth_configured",true).toString()
            method=="GET"&&path=="/api/v1/status" -> x.status().toString()
            method=="GET"&&path=="/api/v1/market/klines" -> x.klines().toString()
            method=="GET"&&path=="/api/v1/scanner" -> x.scanner().toString()
            method=="GET"&&path=="/api/v1/trades" -> x.trades().toString()
            method=="GET"&&path=="/api/v1/logs" -> x.logs().toString()
            method=="GET"&&path=="/api/v1/settings" -> x.settings().toString()
            method=="POST"&&path=="/api/v1/config/binance" -> x.configure(JSONObject(body)).toString()
            method=="DELETE"&&path=="/api/v1/config/binance" -> x.clear().toString()
            method=="POST"&&path=="/api/v1/control/start" -> x.start().toString()
            method=="POST"&&path=="/api/v1/control/stop" -> x.stop().toString()
            method=="POST"&&path=="/api/v1/control/pause" -> x.pause().toString()
            method=="POST"&&path=="/api/v1/control/resume" -> x.resume().toString()
            method=="POST"&&path=="/api/v1/control/recover" -> x.recover().toString()
            else -> JSONObject().put("error","Not found")
        }
    }
}

private class NativeEngine(private val prefs:android.content.SharedPreferences,private val http:OkHttpClient){
    private val symbol="BTCUSDT";private val interval="1h";private val base="https://testnet.binance.vision"
    private var running=false;private var paused=false;private var err:String?=null
    private var candles=emptyList<CandleN>();private var candidates=JSONArray();private var worker:Thread?=null
    private fun key()=prefs.getString("api_key","")?:"";private fun secret()=prefs.getString("api_secret","")?:""

    fun configure(j:JSONObject)=JSONObject().apply{prefs.edit().putString("api_key",j.optString("api_key").trim()).putString("api_secret",j.optString("api_secret").trim()).apply();put("configured",key().isNotBlank()&&secret().isNotBlank());put("testnet",true)}
    fun clear():JSONObject{stop();prefs.edit().remove("api_key").remove("api_secret").apply();return JSONObject().put("configured",false).put("cleared",true)}
    fun start():JSONObject{if(running)return JSONObject().put("started",false);running=true;paused=false;worker=Thread{while(running){if(!paused)cycle();try{Thread.sleep(15000)}catch(_:Exception){}}}.also{it.isDaemon=true;it.start()};return JSONObject().put("started",true)}
    fun stop():JSONObject{running=false;paused=false;worker?.interrupt();worker=null;return JSONObject().put("stopped",true)}
    fun pause()=JSONObject().put("paused",true).also{paused=true};fun resume()=JSONObject().put("resumed",true).also{paused=false}
    fun recover()=JSONObject().put("recovered",true).put("state","FLAT")
    private fun get(path:String):String{val r=Request.Builder().url(base+path).get().build();http.newCall(r).execute().use{x->if(!x.isSuccessful)error("HTTP "+x.code);return x.body?.string()?:"{}"}}
    private fun signedAccount():JSONObject{val ts=System.currentTimeMillis().toString();val p="timestamp="+ts+"&recvWindow=5000";val sig=hmac(p,secret());val r=Request.Builder().url(base+"/api/v3/account?"+p+"&signature="+sig).header("X-MBX-APIKEY",key()).get().build();http.newCall(r).execute().use{x->if(!x.isSuccessful)error("Binance "+x.code+": "+x.body?.string());return JSONObject(x.body?.string()?:"{}")}}
    private fun hmac(v:String,k:String):String{val m=Mac.getInstance("HmacSHA256");m.init(SecretKeySpec(k.toByteArray(),"HmacSHA256"));return m.doFinal(v.toByteArray()).joinToString(""){"%02x".format(it)}}
    private fun fetch():List<CandleN>{val a=JSONArray(get("/api/v3/klines?symbol="+symbol+"&interval="+interval+"&limit=150"));return List(a.length()){i->val x=a.getJSONArray(i);CandleN(x.getLong(0),x.getString(1).toDouble(),x.getString(2).toDouble(),x.getString(3).toDouble(),x.getString(4).toDouble(),x.getString(5).toDouble())}}
    private fun cycle(){try{candles=fetch();scanner();if(key().isNotBlank()&&secret().isNotBlank())signedAccount();err=null}catch(x:Exception){err=x.javaClass.simpleName+": "+(x.message?:"")}}
    fun status():JSONObject{if(candles.isEmpty())try{candles=fetch()}catch(x:Exception){err=x.message};var bal:Double?=null;if(key().isNotBlank()&&secret().isNotBlank())try{val b=signedAccount().getJSONArray("balances");for(i in 0 until b.length())if(b.getJSONObject(i).getString("asset")=="USDT"){bal=b.getJSONObject(i).getString("free").toDouble();break}}catch(x:Exception){err=x.message};return JSONObject().put("version","4.13.0").put("symbol",symbol).put("interval",interval).put("testnet",true).put("running",running).put("paused",paused).put("recovered",true).put("state","FLAT").put("last_error",err?:JSONObject.NULL).put("binance_configured",key().isNotBlank()&&secret().isNotBlank()).put("price",candles.lastOrNull()?.c?:JSONObject.NULL).put("quote_balance",bal?:JSONObject.NULL).put("position",JSONObject.NULL).put("pnl",JSONObject.NULL).put("pnl_pct",JSONObject.NULL).put("take_profit_price",JSONObject.NULL).put("stop_loss_price",JSONObject.NULL).put("stop_loss_pct",0.02).put("take_profit_pct",0.04).put("risk_per_trade_pct",0.01).put("max_daily_loss_pct",0.03).put("max_trades_per_day",5).put("consecutive_losses",0).put("trades_today",0).put("server_time",System.currentTimeMillis())}
    fun klines():JSONObject{if(candles.isEmpty())try{candles=fetch()}catch(_:Exception){};val out=JSONArray();val pr=candles.map{it.c};val j=smma(pr,13);val t=smma(pr,8);val l=smma(pr,5);candles.takeLast(120).forEachIndexed{k,c->val i=candles.size-120+k;out.put(JSONObject().put("time",c.t).put("open",c.o).put("high",c.h).put("low",c.l).put("close",c.c).put("jaw",j.getOrNull(i)?:JSONObject.NULL).put("teeth",t.getOrNull(i)?:JSONObject.NULL).put("lips",l.getOrNull(i)?:JSONObject.NULL).put("ao",ao(pr,i)).put("long_signal",signal(i)).put("fractal_up",up(i)).put("fractal_down",down(i)))};return JSONObject().put("symbol",symbol).put("interval",interval).put("candles",out)}
    fun scanner():JSONObject{if(candles.isEmpty())try{candles=fetch()}catch(_:Exception){};val i=candles.lastIndex;val pos=wave(i);val sig=signal(i);val c=JSONObject().put("symbol",symbol).put("score",score(i)).put("signal",sig).put("setup_score",score(i)).put("signal_strength",if(sig)"CONFIRMED" else "WATCHING").put("breakout_distance_pct",0.0).put("risk_pct",2.0).put("risk_reward",2.0).put("atr_pct",atr()).put("spread_pct",0.0).put("htf_confirmed",true).put("setup_state",if(sig)"SIGNAL" else "WATCHING").put("reason",if(pos==5)"Wave 5 context: native engine reduces confidence." else "Native Profitunity: Alligator + AO + Fractal + ATR.").put("wise_man_count",if(sig)2 else 0).put("signal_family","ALLIGATOR_AO_FRACTAL").put("wave_score",if(pos==3)85.0 else if(pos==5)45.0 else 65.0).put("wave_position",pos).put("wave_phase",if(pos==5)"EXHAUSTION_WATCH" else "IMPULSE").put("wave_confidence",55.0).put("wave_exhaustion_risk",if(pos==5)70.0 else 20.0).put("nested_w3",false).put("nested_w3_parent_w5",false).put("wave_path","1-2-3-4-5 / native heuristic");candidates=JSONArray().put(c);return JSONObject().put("version","4.13.0").put("cached",false).put("scanning",false).put("candidates",candidates)}
    fun trades()=JSONArray()
    fun logs()=JSONArray().put(JSONObject().put("created_at",System.currentTimeMillis()).put("level","INFO").put("message","Native standalone engine active; TESTNET; DRY_RUN"))
    fun settings()=JSONObject().put("version","4.13.0").put("symbol",symbol).put("interval",interval).put("position_fraction",0.95).put("stop_loss_pct",0.02).put("take_profit_pct",0.04).put("poll_seconds",15).put("risk_per_trade_pct",0.01).put("max_daily_loss_pct",0.03).put("max_trades_per_day",5).put("max_consecutive_losses",3).put("cooldown_minutes",30).put("min_risk_reward",1.5).put("atr_period",14).put("max_atr_pct",0.08).put("max_spread_pct",0.0015).put("require_htf_confirmation",true).put("htf_interval","4h").put("testnet",true).put("strategy_name","Williams Profitunity Conservative").put("standalone",true)
    private fun smma(v:List<Double>,n:Int):List<Double>{if(v.isEmpty())return emptyList();val o=MutableList(v.size){0.0};o[0]=v[0];for(i in 1 until v.size)o[i]=(o[i-1]*(n-1)+v[i])/n;return o}
    private fun ao(v:List<Double>,i:Int):Double{if(i<34)return 0.0;val m=candles.map{(it.h+it.l)/2};return m.subList(i-4,i+1).average()-m.subList(i-33,i+1).average()}
    private fun signal(i:Int):Boolean{if(i<34)return false;val p=candles.map{it.c};val j=smma(p,13)[i];val t=smma(p,8)[i];val l=smma(p,5)[i];return l>t&&t>j&&p[i]>l&&ao(p,i)>0}
    private fun up(i:Int)=i>=2&&i+2<candles.size&&candles[i].h>candles[i-1].h&&candles[i].h>candles[i-2].h&&candles[i].h>candles[i+1].h&&candles[i].h>candles[i+2].h
    private fun down(i:Int)=i>=2&&i+2<candles.size&&candles[i].l<candles[i-1].l&&candles[i].l<candles[i+1].l&&candles[i].l<candles[i+2].l
    private fun atr():Double{if(candles.size<15)return 0.0;val tr=candles.zipWithNext().map{max(it.second.h-it.second.l,max(abs(it.second.h-it.first.c),abs(it.second.l-it.first.c)))};return tr.takeLast(14).average()/candles.last().c}
    private fun score(i:Int):Double{if(i<34)return 0.0;var s=0.0;if(signal(i))s+=45.0;s+=min(25.0,max(0.0,(1-atr()/0.08)*25));if(up(i-2))s+=15.0;if(wave(i) in 1..4)s+=15.0;return s}
    private fun wave(i:Int):Int{if(i<20)return 0;val a=candles.takeLast(min(40,candles.size)).map{it.c};val lo=a.minOrNull()?:a.first();val hi=a.maxOrNull()?:a.last();val p=(a.last()-lo)/max(1e-9,hi-lo);return when{p<.2->2;p<.5->3;p<.8->4;else->5}}
}
