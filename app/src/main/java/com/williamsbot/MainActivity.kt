package com.williamsbot

import android.content.Context
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { WilliamsApp(this) } }
}

data class Status(val symbol:String="-",val interval:String="-",val testnet:Boolean=true,val running:Boolean=false,val paused:Boolean=false,val recovered:Boolean=false,val state:String="FLAT",val price:Double?=null,val balance:Double?=null,val qty:Double?=null,val entry:Double?=null,val tp:Double?=null,val sl:Double?=null,val pnl:Double?=null,val pnlPct:Double?=null,val error:String?=null,val serverTime:String="",val wsConnected:Boolean=false,val binanceConfigured:Boolean=false,val riskPerTrade:Double=0.01,val maxDailyLoss:Double=0.03,val tradesToday:Int=0,val maxTrades:Int=5,val consecutiveLosses:Int=0,val maxConsecutiveLosses:Int=3)
data class Candle(val time:String,val open:Double,val close:Double,val high:Double,val low:Double,val jaw:Double?,val teeth:Double?,val lips:Double?,val longSignal:Boolean,val fractalUp:Boolean,val fractalDown:Boolean)
data class Trade(val id:String,val side:String,val entry:Double?,val exit:Double?,val pnl:Double?,val reason:String)

class SecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(context,"williams_secure",MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun get(k:String,d:String="")=prefs.getString(k,d) ?: d
    fun put(k:String,v:String){prefs.edit().putString(k,v).apply()}
    fun getBool(k:String,d:Boolean)=prefs.getBoolean(k,d)
    fun putBool(k:String,v:Boolean){prefs.edit().putBoolean(k,v).apply()}
}

class Api(private val base:String,private val token:String){
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    fun get(path:String)=request("GET",path,null)
    fun post(path:String,body:String?=null)=request("POST",path,body)
    fun delete(path:String)=request("DELETE",path,null)
    private fun request(method:String,path:String,body:String?):String{
        val b=Request.Builder().url(base.trimEnd('/')+path).header("Authorization","Bearer $token")
        val rb=body?.toRequestBody("application/json".toMediaType())
        val req=b.method(method,if(method=="POST") rb ?: "".toRequestBody(null) else null).build()
        client.newCall(req).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}: ${r.body?.string()}");return r.body?.string() ?: "{}"}
    }
}

class ReconnectingSocket(private val client:OkHttpClient,private val request:Request,private val onMessage:(JSONObject)->Unit,private val onState:(Boolean,String?)->Unit):WebSocketListener(){
    @Volatile private var stopped=true
    private var socket:WebSocket?=null
    private var attempt=0
    private val scheduler=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    fun start(){stopped=false;attempt=0;connect()}
    fun stop(){stopped=true;socket?.close(1000,"screen closed");scheduler.shutdownNow()}
    private fun connect(){if(stopped)return;socket=client.newWebSocket(request,this)}
    private fun schedule(reason:String){
        if(stopped)return
        val delay=(1000L shl attempt.coerceAtMost(5)).coerceAtMost(30000L)
        attempt++
        onState(false,reason)
        scheduler.schedule({connect()},delay,java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    override fun onOpen(ws:WebSocket,response:Response){attempt=0;socket=ws;onState(true,null);ws.send("ping")}
    override fun onMessage(ws:WebSocket,text:String){try{onMessage(JSONObject(text))}catch(_:Exception){}}
    override fun onClosing(ws:WebSocket,code:Int,reason:String){schedule("WebSocket закрывается ($code)")}
    override fun onClosed(ws:WebSocket,code:Int,reason:String){schedule("WebSocket закрыт ($code)")}
    override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){schedule(t.message ?: "WebSocket error")}
}


@Composable fun WilliamsApp(context:Context){
    val store=remember{SecureStore(context)}
    var host by remember{mutableStateOf(store.get("host",""))}
    var token by remember{mutableStateOf(store.get("token",""))}
    var apiKey by remember{mutableStateOf("")}
    var apiSecret by remember{mutableStateOf("")}
    var testnet by remember{mutableStateOf(store.getBool("testnet",true))}
    var status by remember{mutableStateOf(Status(testnet=testnet))};var candles by remember{mutableStateOf(emptyList<Candle>())};var trades by remember{mutableStateOf(emptyList<Trade>())};var logs by remember{mutableStateOf(emptyList<String>())};var message by remember{mutableStateOf("Подключение…")};var tab by remember{mutableIntStateOf(0)};var refreshing by remember{mutableStateOf(false)};val scanner=remember{GmsBarcodeScanning.getClient(context)}
    val scope=rememberCoroutineScope();val httpClient=remember{OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()}
    fun api()=Api(host,token)
    fun applySnapshot(j:JSONObject){val p=j.optJSONObject("position");status=status.copy(symbol=j.optString("symbol",status.symbol),interval=j.optString("interval",status.interval),testnet=j.optBoolean("testnet",status.testnet),running=j.optBoolean("running",status.running),paused=j.optBoolean("paused",status.paused),recovered=j.optBoolean("recovered",status.recovered),state=j.optString("state",status.state),price=j.optDouble("price").takeUnless{it.isNaN()},balance=j.optDouble("quote_balance").takeUnless{it.isNaN()},qty=p?.optDouble("quantity")?.takeUnless{it.isNaN()},entry=p?.optDouble("entry_price")?.takeUnless{it.isNaN()},tp=j.optDouble("take_profit_price").takeUnless{it.isNaN()||it==0.0},sl=j.optDouble("stop_loss_price").takeUnless{it.isNaN()||it==0.0},pnl=j.optDouble("pnl").takeUnless{it.isNaN()},pnlPct=j.optDouble("pnl_pct").takeUnless{it.isNaN()},error=j.optString("last_error").takeIf{it.isNotBlank()},serverTime=j.optString("server_time",status.serverTime),wsConnected=j.optBoolean("ws_connected",status.wsConnected),binanceConfigured=j.optBoolean("binance_configured",status.binanceConfigured),riskPerTrade=j.optDouble("risk_per_trade_pct",status.riskPerTrade),maxDailyLoss=j.optDouble("max_daily_loss_pct",status.maxDailyLoss),tradesToday=j.optInt("trades_today",status.tradesToday),maxTrades=j.optInt("max_trades_per_day",status.maxTrades),consecutiveLosses=j.optInt("consecutive_losses",status.consecutiveLosses),maxConsecutiveLosses=j.optInt("max_consecutive_losses",status.maxConsecutiveLosses));j.optJSONArray("candles")?.let{a->candles=List(a.length()){i->val x=a.getJSONObject(i);Candle(x.optString("time"),x.optDouble("open"),x.optDouble("close"),x.optDouble("high"),x.optDouble("low"),x.optDouble("jaw").takeUnless{it.isNaN()},x.optDouble("teeth").takeUnless{it.isNaN()},x.optDouble("lips").takeUnless{it.isNaN()},x.optBoolean("long_signal"),x.optBoolean("fractal_up"),x.optBoolean("fractal_down"))}}}
    fun refresh(){scope.launch(Dispatchers.IO){try{refreshing=true;val a=api();val j=JSONObject(a.get("/api/v1/status"));val k=JSONObject(a.get("/api/v1/market/klines"));val snap=JSONObject(j.toString()).apply{put("candles",k.getJSONArray("candles"))};val ta=JSONArray(a.get("/api/v1/trades"));val la=JSONArray(a.get("/api/v1/logs"));val nt=List(ta.length()){i->val x=ta.getJSONObject(i);Trade(x.optString("id"),x.optString("side"),x.optDouble("entry_price").takeUnless{it.isNaN()},x.optDouble("exit_price").takeUnless{it.isNaN()},x.optDouble("pnl").takeUnless{it.isNaN()},x.optString("reason"))};val nl=List(la.length()){i->val x=la.getJSONObject(i);"${x.optString("created_at")} ${x.optString("level")} ${x.optString("message")}" };withContext(Dispatchers.Main){applySnapshot(snap);trades=nt;logs=nl;refreshing=false}}catch(e:Exception){withContext(Dispatchers.Main){message=e.message?:"Ошибка соединения";refreshing=false}}}}
    fun command(path:String){scope.launch(Dispatchers.IO){try{api().post(path);refresh()}catch(e:Exception){withContext(Dispatchers.Main){message=e.message?:"Ошибка"}}}}
    fun applyPairing(raw:String){
        try{
            val u=Uri.parse(raw.trim())
            if(u.scheme!="williams" || u.host!="connect") throw IllegalArgumentException("Неверный QR-код Williams")
            val newHost=u.getQueryParameter("url")?.trim()?.trimEnd('/') ?: throw IllegalArgumentException("В QR нет адреса сервера")
            val newToken=u.getQueryParameter("token")?.trim() ?: throw IllegalArgumentException("В QR нет токена")
            require(newHost.startsWith("https://")) { "Для удалённого подключения нужен HTTPS" }
            require(newToken.length>=32) { "Неверный Mobile API Token" }
            host=newHost; token=newToken; store.put("host",newHost); store.put("token",newToken); message="Сервер подключён через QR"; tab=0
        }catch(e:Exception){message=e.message ?: "Не удалось прочитать QR"}
    }
    fun scanPairing(){scanner.startScan().addOnSuccessListener{barcode->barcode.rawValue?.let{applyPairing(it)}}.addOnFailureListener{e->message=e.message ?: "QR-сканер недоступен"}}
    fun configure(){scope.launch(Dispatchers.IO){try{val body=JSONObject().put("api_key",apiKey).put("api_secret",apiSecret).put("testnet",testnet).toString();api().post("/api/v1/config/binance",body);store.put("host",host);store.put("token",token);store.putBool("testnet",testnet);apiKey="";apiSecret="";withContext(Dispatchers.Main){message="Binance ключи приняты сервером (Testnet=${testnet})"};refresh()}catch(e:Exception){withContext(Dispatchers.Main){message=e.message?:"Ошибка конфигурации"}}}}
    DisposableEffect(host,token){if(token.isBlank()){onDispose{} } else {refresh();val wsUrl=host.trimEnd('/').replaceFirst(Regex("^http://"),"ws://").replaceFirst(Regex("^https://"),"wss://")+"/api/v1/ws";val listener=ReconnectingSocket(httpClient,Request.Builder().url(wsUrl).header("Authorization","Bearer $token").build(),{root->scope.launch(Dispatchers.Main){when(root.optString("type")){"snapshot","ticker","account"->root.optJSONObject("data")?.let{applySnapshot(it);if(root.optString("type")=="snapshot")message="WebSocket: realtime"};"candle"->root.optJSONObject("data")?.let{x->val c=Candle(x.optString("time"),x.optDouble("open"),x.optDouble("close"),x.optDouble("high"),x.optDouble("low"),x.optDouble("jaw").takeUnless{it.isNaN()},x.optDouble("teeth").takeUnless{it.isNaN()},x.optDouble("lips").takeUnless{it.isNaN()},x.optBoolean("long_signal"),x.optBoolean("fractal_up"),x.optBoolean("fractal_down"));candles=(candles.filterNot{it.time==c.time}+c).takeLast(120)}}}},{connected,error->scope.launch(Dispatchers.Main){status=status.copy(wsConnected=connected,error=error);if(connected)message="WebSocket: realtime" else if(error!=null)message=error}});listener.start();onDispose{listener.stop()}}}
    LaunchedEffect(Unit){if(token.isNotBlank()&&host.isNotBlank()){refresh()}}
    LaunchedEffect(tab){if(tab==2||tab==3)refresh()}
    MaterialTheme(colorScheme=darkColorScheme()){
        if(host.isBlank() || token.isBlank()){
            SetupScreen(testnet=testnet, onTestnetChange={testnet=it}, onScan=::scanPairing, host=host, onHost={host=it}, token=token, onToken={token=it}, apiKey=apiKey, onApiKey={apiKey=it}, apiSecret=apiSecret, onApiSecret={apiSecret=it}, onConfigure=::configure, message=message)
        } else {
            Scaffold(topBar={TopAppBar(title={Column{Text("Williams Trader");Text("${status.symbol} · ${status.interval}",style=MaterialTheme.typography.labelSmall)}},actions={Text(if(status.testnet)"TESTNET" else "LIVE",color=if(status.testnet)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,modifier=Modifier.padding(end=12.dp))})},bottomBar={NavigationBar{listOf("Обзор","График","Сделки","Логи","Настройки").forEachIndexed{i,n->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(if(i==0)Icons.Default.Home else if(i==1)Icons.Default.ShowChart else if(i==2)Icons.Default.List else if(i==3)Icons.Default.Terminal else Icons.Default.Settings,n)},label={Text(n)})}}}){pad->LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)){when(tab){0->dashboard(status,::command,message,refreshing);1->{item{ChartHeader(status,candles.size)};item{TradingChart(candles,status)}};2->items(trades){TradeCard(it)};3->items(logs){Text(it,style=MaterialTheme.typography.bodySmall)};4->{item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Подключение к VPS",style=MaterialTheme.typography.titleMedium);Text("Самый простой способ — отсканировать QR-код, который выдаёт установщик VPS.",style=MaterialTheme.typography.bodySmall);Button(onClick=::scanPairing,modifier=Modifier.fillMaxWidth()){Text("СКАНИРОВАТЬ QR")};Text("Если QR недоступен, URL и token можно ввести ниже вручную.",style=MaterialTheme.typography.bodySmall)}}};item{OutlinedTextField(host,{host=it},label={Text("Backend URL")},singleLine=true,modifier=Modifier.fillMaxWidth())};item{OutlinedTextField(token,{token=it},label={Text("Mobile API token")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())};item{OutlinedTextField(apiKey,{apiKey=it},label={Text("Binance API Key")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())};item{OutlinedTextField(apiSecret,{apiSecret=it},label={Text("Binance API Secret")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())};item{Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(testnet,{testnet=it});Text("Binance Testnet")}};item{Button(onClick=::configure,enabled=apiKey.isNotBlank()&&apiSecret.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Сохранить и передать ключи")}};item{OutlinedButton(onClick={scope.launch(Dispatchers.IO){try{api().post("/api/v1/control/stop");api().delete("/api/v1/config/binance");withContext(Dispatchers.Main){apiKey="";apiSecret="";message="Binance credentials удалены"};refresh()}catch(e:Exception){withContext(Dispatchers.Main){message=e.message?:"Ошибка удаления"}}}},modifier=Modifier.fillMaxWidth()){Text("Отключить Binance и удалить ключи")}};item{Button(onClick={ refresh() },modifier=Modifier.fillMaxWidth()){Text("Подключить / обновить")}};item{Text("Binance-ключи не сохраняются на телефоне: после передачи они хранятся на VPS в зашифрованном виде. Для удалённого сервера используй только HTTPS/WSS. HTTP допускается только для локального Testnet.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}}
        }
    }
}
@Composable
fun SetupScreen(testnet:Boolean,onTestnetChange:(Boolean)->Unit,onScan:()->Unit,host:String,onHost:(String)->Unit,token:String,onToken:(String)->Unit,apiKey:String,onApiKey:(String)->Unit,apiSecret:String,onApiSecret:(String)->Unit,onConfigure:()->Unit,message:String){
    LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=24.dp)){
        item{Text("WILLIAMS TRADER",style=MaterialTheme.typography.headlineMedium);Text("Автоматическая настройка",style=MaterialTheme.typography.titleMedium);Text("Сначала подключим VPS, затем Binance. Компьютер для работы бота не нужен.",style=MaterialTheme.typography.bodyMedium)}
        item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Шаг 1 · VPS",style=MaterialTheme.typography.titleMedium);Text("На VPS запустите install_no_domain.sh. Он создаст защищённое подключение и QR-код.",style=MaterialTheme.typography.bodySmall);Button(onClick=onScan,modifier=Modifier.fillMaxWidth()){Text("СКАНИРОВАТЬ QR")}}}}
        item{Text("Или введите данные вручную",style=MaterialTheme.typography.labelLarge)}
        item{OutlinedTextField(host,onHost,label={Text("Backend URL")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(token,onToken,label={Text("Mobile API token")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())}
        item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Шаг 2 · Binance",style=MaterialTheme.typography.titleMedium);OutlinedTextField(apiKey,onApiKey,label={Text("API Key")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(apiSecret,onApiSecret,label={Text("API Secret")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(testnet,onTestnetChange);Text("Использовать Binance Testnet")};Text("Секрет после передачи удаляется из полей приложения и хранится на VPS в зашифрованном виде.",style=MaterialTheme.typography.bodySmall)}}}
        item{Button(onClick=onConfigure,enabled=host.isNotBlank()&&token.length>=32&&apiKey.isNotBlank()&&apiSecret.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("ПОДКЛЮЧИТЬ И СОХРАНИТЬ")}}
        item{Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{Text("Для первого запуска оставьте Testnet. Реальная торговля не включается автоматически.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)}
    }
}

fun fmt(v:Double?,d:Int=2)=if(v==null)"—" else String.format(Locale.US,"%.${d}f",v)
fun pct(v:Double?)=if(v==null)"—" else String.format(Locale.US,"%+.2f%%",v*100)
fun androidx.compose.foundation.lazy.LazyListScope.dashboard(s:Status,cmd:(String)->Unit,msg:String,refreshing:Boolean){item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StatusChip(if(!s.running)"STOPPED" else if(s.paused)"PAUSED" else "RUNNING");StatusChip(if(s.state=="RECONCILE_REQUIRED")"⚠ RECONCILE" else if(s.recovered)"RECOVERY OK" else "SYNCING");if(refreshing)Text("↻");StatusChip(if(s.wsConnected)"WS LIVE" else "WS OFF")}};item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text(s.symbol,style=MaterialTheme.typography.titleMedium);Text("${fmt(s.price)} USDT",style=MaterialTheme.typography.headlineLarge);Text("Баланс ${fmt(s.balance)} USDT")}}};item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Позиция");Text(if(s.qty!=null)"LONG" else "FLAT")};if(s.qty!=null){InfoRow("Количество",fmt(s.qty,6));InfoRow("Вход",fmt(s.entry));InfoRow("TP",fmt(s.tp));InfoRow("SL",fmt(s.sl));InfoRow("PnL",fmt(s.pnl)+" USDT");InfoRow("Доходность",pct(s.pnlPct))}else Text("Открытой позиции нет")}}};item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={cmd("/api/v1/control/start")},enabled=s.binanceConfigured&&!s.running,modifier=Modifier.weight(1f)){Text("START")};OutlinedButton(onClick={cmd("/api/v1/control/pause")},enabled=s.running,modifier=Modifier.weight(1f)){Text("PAUSE")}}};item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={cmd("/api/v1/control/resume")},modifier=Modifier.weight(1f)){Text("RESUME")};OutlinedButton(onClick={cmd("/api/v1/control/stop")},modifier=Modifier.weight(1f)){Text("STOP")};OutlinedButton(onClick={cmd("/api/v1/control/recover")},modifier=Modifier.weight(1f)){Text("RECOVER")}}};item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("Риск",style=MaterialTheme.typography.titleSmall);InfoRow("Риск сделки",String.format(Locale.US,"%.2f%%",s.riskPerTrade*100));InfoRow("Сделки сегодня","${s.tradesToday}/${s.maxTrades}");InfoRow("Убытки подряд","${s.consecutiveLosses}/${s.maxConsecutiveLosses}");InfoRow("Дневной лимит",String.format(Locale.US,"%.2f%%",s.maxDailyLoss*100))}}};item{Text("State: ${s.state}",style=MaterialTheme.typography.bodySmall,color=if(s.state=="RECONCILE_REQUIRED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)};item{Text(if(s.binanceConfigured) "Binance: подключён" else "Binance: ключи не настроены",style=MaterialTheme.typography.bodySmall,color=if(s.binanceConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)};item{Text(msg,style=MaterialTheme.typography.bodySmall)};item{s.error?.let{Text("Ошибка: $it",color=MaterialTheme.colorScheme.error)}}}
@Composable fun InfoRow(k:String,v:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(k);Text(v)}}
@Composable fun StatusChip(t:String){Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant){Text(t,Modifier.padding(horizontal=12.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium)}}
@Composable fun ChartHeader(s:Status,n:Int){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${s.symbol} · ${s.interval}");Text("$n свечей")}}
@Composable fun TradeCard(t:Trade){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text("#${t.id} ${t.side}");Text("Entry ${fmt(t.entry)}  Exit ${fmt(t.exit)}");Text("PnL ${fmt(t.pnl)}  ${t.reason}")}}}
@Composable
fun TradingChart(c: List<Candle>, s: Status) {
    if (c.isEmpty()) { Text("Нет рыночных данных"); return }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    val colors = MaterialTheme.colorScheme
    Canvas(
        Modifier.fillMaxWidth().height(390.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .pointerInput(c) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = (offset + pan.x).coerceIn(-700f, 700f)
                }
            }
    ) {
        val count = (c.size / scale).toInt().coerceAtLeast(25).coerceAtMost(c.size)
        val start = (c.size - count - (offset / 10f).toInt()).coerceIn(0, c.size - count)
        val v = c.subList(start, start + count)
        val levels = listOfNotNull(s.tp, s.entry, s.sl)
        val minP = min(v.minOf { it.low }, levels.minOrNull() ?: Double.MAX_VALUE)
        val maxP = max(v.maxOf { it.high }, levels.maxOrNull() ?: Double.MIN_VALUE)
        val range = max(1e-9, maxP - minP)
        fun y(p: Double): Float = (size.height - (p - minP) / range * size.height).toFloat()
        fun x(i: Int) = if (v.size == 1) size.width / 2 else i.toFloat() / (v.size - 1) * size.width
        for (i in v.indices) {
            val q = v[i]
            drawLine(colors.outline, Offset(x(i), y(q.high)), Offset(x(i), y(q.low)), 1f)
            val top = y(max(q.open, q.close)); val bot = y(min(q.open, q.close))
            drawRect(if (q.close >= q.open) colors.primary else colors.error, Offset(x(i) - 3, top), androidx.compose.ui.geometry.Size(6f, max(2f, bot - top)))
        }
        fun line(sel: (Candle) -> Double?, col: Color) {
            val p = Path(); var begun = false
            v.forEachIndexed { i, q ->
                sel(q)?.let { z -> if (!begun) { p.moveTo(x(i), y(z)); begun = true } else p.lineTo(x(i), y(z)) }
            }
            if (begun) drawPath(p, col, style = androidx.compose.ui.graphics.drawscope.Stroke(2f, cap = StrokeCap.Round))
        }
        line({ it.jaw }, colors.tertiary)
        line({ it.teeth }, colors.secondary)
        line({ it.lips }, colors.primary)
        fun level(p: Double?, col: Color, label: String) {
            if (p == null) return
            val yy = y(p)
            drawLine(col, Offset(0f, yy), Offset(size.width, yy), 2f)
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText("$label ${fmt(p)}", 12f, yy - 6f, android.graphics.Paint().apply { color = col.toArgb(); textSize = 28f }) }
        }
        level(s.entry, colors.primary, "BUY")
        level(s.tp, colors.tertiary, "TP")
        level(s.sl, colors.error, "SL")
    }
}

