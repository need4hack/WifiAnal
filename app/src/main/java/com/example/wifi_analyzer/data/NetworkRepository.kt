package com.example.wifi_analyzer.data

/*import java.net.InetSocketAddress
import java.net.Socket
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.Inet4Address
import java.net.InetAddress
import android.net.wifi.WifiInfo
import com.example.wifi_analyzer.database.AppDatabase
import com.example.wifi_analyzer.database.ScanHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import okhttp3.OkHttpClient
import okhttp3.Request
*/
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresPermission
import com.example.wifi_analyzer.database.AppDatabase
import com.example.wifi_analyzer.database.ScanHistoryEntity
import jcifs.context.SingletonContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS

// Модель для информации о текущем подключении
data class CurrentNetworkInfo(
    val ssid: String = "Не подключено",
    val myIp: String = "?.?.?.?",
    val routerIp: String = "?.?.?.?"
)

// Модель для найденного устройства
data class FoundDevice(
    val ip: String,
    val hostname: String
)

//для cканирования портов
// Статусы для UI
enum class PortStatus { OPEN, CLOSED }

// Модель для результата сканирования одного порта
data class PortResult(
    val port: Int,
    val serviceName: String,
    val status: PortStatus
)

class NetworkRepository(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // Кэш для SSDP ответов (Map<IP, Name>)
    private val ssdpCache = ConcurrentHashMap<String, String>()
    /**
     * Получает информацию о ТЕКУЩЕМ подключении (SSID, IP, Роутер)
     */
    @RequiresPermission(allOf = [
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
    ])
    fun getCurrentNetworkInfo(): CurrentNetworkInfo {
        // 1. Получаем LinkProperties для IP и роутера
        val linkProperties =
            connectivityManager.getLinkProperties(connectivityManager.activeNetwork)

        // 2. Ищем наш IP-адрес (IPv4)
        val myIpAddress = linkProperties?.linkAddresses
            ?.find { it.address is Inet4Address }
            ?.address
            ?.hostAddress ?: "?.?.?.?"

        // 3. Ищем IP роутера (шлюз по умолчанию)
        val routerIpAddress = linkProperties?.routes
            ?.find { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress ?: "?.?.?.?"

        // 4. Получаем SSID (имя Wi-Fi)
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // На Android 10+ нужен этот метод, и он возвращает "<unknown ssid>",
            // если у приложения нет разрешения на FINE_LOCATION.
            // Но мы попробуем, это лучше, чем ничего.
            wifiManager.connectionInfo.ssid
        } else {
            wifiManager.connectionInfo.ssid
        }

        // Очищаем SSID от лишних кавычек (e.g. "My_WiFi" -> My_WiFi)
        val cleanSsid = ssid.removeSurrounding("\"").takeIf { it != "<unknown ssid>" }
            ?: "Wi-Fi (без имени)"

        return CurrentNetworkInfo(
            ssid = cleanSsid,
            myIp = myIpAddress,
            routerIp = routerIpAddress
        )
    }

    /**
     * Сканирует локальную сеть.
     * Возвращает Flow, который эмитит устройства по одному.
     */
    @RequiresPermission("android.permission.INTERNET")
    fun scanLocalNetwork(myIp: String): Flow<FoundDevice> = flow {
        if (!myIp.contains(".")) {
            throw Exception("Неверный IP-адрес для сканирования")
        }

        val subnet = myIp.substringBeforeLast('.')

        //Слушаем SSDP в отдельной корутине на пару сек. При запуске должно заполнить ssdpCache именами типа "Samsung TV"
        val ssdpJob = CoroutineScope(Dispatchers.IO).launch {
            discoverSSDP()
        }
        delay(500) //небольшая пауза для того чтобы SSDP успел собрать первые ответы

        coroutineScope {
            val checkJobs = (1..254).map{i ->
                async(Dispatchers.IO){
                val host = "$subnet.$i"

                // Не пингуем сами себя
                if (host == myIp) return@async null

                if (isHostAlive(host)){
                    // Таймаут 500мс. Если хост не ответил, идем дальше.
                    val name = resolveDeviceName(host)
                    return@async FoundDevice(host, name)
                    }
                    return@async null // Игнорируем недостижимые хосты
                }
            }
            checkJobs.forEach { job ->
                val device = job.await()
                if(device != null){
                    emit(device)
                    // Игнорируем недостижимые хосты
                }
            }
        }
        ssdpJob.cancel()
    }.flowOn(Dispatchers.IO) // ВАЖНО: вся работа с сетью - в фоновом потоке

    //Методы определения имени устройства
    private fun resolveDeviceName(ip: String): String{
        //проверка ответило ли устройство по SSDP
        if(ssdpCache.containsKey(ip)){
            return ssdpCache[ip]!!
        }
        //netBIOS-эффективно для ПК
        val netBiosName = getNetBiosName(ip)
        if (netBiosName != null) return netBiosName

        //mDNS - устройства Apple, IoT
        val mdnsName = getMdnsName(ip)
        if(mdnsName != null) return mdnsName

        //Http заголовок(роутер, камеры и т.д)
        val httpTitle = getHttpTitle(ip)
        if(httpTitle != null) return httpTitle

        //Обычный стандартный DNS
        return try {
            val inetAddr = InetAddress.getByName(ip)
            val hostname = inetAddr.canonicalHostName
            if(hostname != ip) hostname else ip// Если имя совпадает с ip значит не нашли
        } catch (e: Exception){
            ip
        }
    }

    //Реализация самих протоколов

    //NetBIOS (SMB)
    private fun getNetBiosName(ip: String): String?{
        return try{
            val context = SingletonContext.getInstance()
            val addrs = context.nameServiceClient.getNbtAllByAddress(ip)
            if(addrs.isNotEmpty()){
                addrs[0].hostName
            }
            else null
        }
        catch (e: Exception){
            null
        }
    }

    //mDNS (JmDNS)
    private fun getMdnsName(ip: String): String?{
        return null
    }

    //UPnP/SSDP Discovery
    private fun discoverSSDP(){
        var socket: DatagramSocket? = null
        try{
            socket = DatagramSocket()
            socket.soTimeout = 2000 //ожидание ответа
            //M-search пакет
            val query = "M-SEARCH * HTTP/1.1\\r\\n\" +\n" +
                    "                    \"HOST: 239.255.255.250:1900\\r\\n\" +\n" +
                    "                    \"MAN: \\\"ssdp:discover\\\"\\r\\n\" +\n" +
                    "                    \"MX: 1\\r\\n\" +\n" +
                    "                    \"ST: ssdp:all\\r\\n\" + \n" +
                    "                    \"\\r\\n"
            val addr = InetAddress.getByName("239.255.255.250")
            val sendPacket = DatagramPacket(query.toByteArray(), query.length, addr, 1900)
            socket.send(sendPacket)

            val buffer = ByteArray(2048)
            while (true){
                val receivePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(receivePacket)
                val response = String(receivePacket.data, 0, receivePacket.length)
                val remoteIp = receivePacket.address.hostAddress

                //Парсим имя ои ответа
                var serverName = parseHeaderValue(response, "SERVER") ?: "UPnP Device"

                if(remoteIp != null){
                    ssdpCache[remoteIp] = serverName
                }
            }
        } catch (e: Exception){

        } finally {
            socket?.close()
        }
    }

    //Http Title - парсим title с порта 80
    private fun getHttpTitle(ip: String): String?{
        val client = OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url("http://$ip").build()
        return try {
            client.newCall(request).execute().use {response ->
                if (response.isSuccessful){
                    val body = response.body?.string() ?: ""
                    val regex = "<title>(.*?)</title>".toRegex(RegexOption.IGNORE_CASE)
                    regex.find(body)?.groupValues?.get(1)?.trim()
                } else null
            }
        } catch (e: Exception){
            null
        }
    }

    //что то типо парсера
    private fun parseHeaderValue(content: String, headerName: String): String?{
        val lines = content.lines()
        for (line in lines){
            if(line.startsWith(headerName, ignoreCase = true)){
                return line.substringAfter(":").trim()
            }
        }
        return null
    }

    //проверка доступности хоста
    private fun isHostAlive(host: String): Boolean {
        try {
            val addr = InetAddress.getByName(host)
            // 1. Быстрый ICMP (если есть права/рут)
            if (addr.isReachable(200)) return true

            // 2. Если пинг закрыт, пробуем TCP Connect на популярных портах
            // Порты: 80 (Web), 445 (SMB), 135 (RPC), 22 (SSH)
            val ports = listOf(80, 445, 135, 62078) // 62078 часто открыт на iPhone
            for (port in ports) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 150)
                    }
                    return true // Если хоть один порт открыт - хост жив
                } catch (e: Exception) { /* Port closed */ }
            }
        } catch (e: Exception) { return false }
        return false
    }

    fun scanPorts(ip: String): Flow<PortResult> = flow {
        // Список популярных портов для проверки
        val popularPorts = mapOf(
            21 to "FTP",
            22 to "SSH",
            23 to "Telnet",
            25 to "SMTP",
            53 to "DNS",
            80 to "HTTP",
            110 to "POP3",
            135 to "Microsoft RPC",
            139 to "NetBIOS",
            443 to "HTTPS",
            445 to "SMB",
            1433 to "MSSQL",
            3306 to "MySQL",
            3389 to "RDP",
            5432 to "PostgreSQL",
            8080 to "HTTP-Alt"
        )

        for ((port, serviceName) in popularPorts) {
            try {
                // Создаем сокет и пытаемся подключиться
                Socket().use { socket -> // use { } - для авто-закрытия сокета
                    // Устанавливаем ОЧЕНЬ короткий таймаут, чтобы не ждать вечно
                    val socketAddress = InetSocketAddress(ip, port)
                    socket.connect(socketAddress, 200) // 200 миллисекунд
                }
                // Если connect() прошел без ошибки - порт ОТКРЫТ
                emit(PortResult(port, serviceName, PortStatus.OPEN))

            } catch (e: Exception) {
                // Любая ошибка (timeout, connection refused) = порт ЗАКРЫТ
                emit(PortResult(port, serviceName, PortStatus.CLOSED))
            }
        }
    }.flowOn(Dispatchers.IO) // Вся работа с сокетами ТОЛЬКО в IO-потоке

    private val historyDao = AppDatabase.getDatabase(context).scanHistoryDao()

    // --- МЕТОДЫ ИСТОРИИ ---

    // 1. Сохранить сеть в историю
    suspend fun saveNetworkToHistory(ssid: String) {
        if (ssid.isBlank() || ssid == "<unknown ssid>" || ssid == "Wi-Fi") return

        // Можно добавить проверку, чтобы не дублировать подряд одну и ту же сеть
        // Но пока просто пишем всё
        historyDao.insert(ScanHistoryEntity(ssid = ssid))
    }

    // 2. Читать историю (Flow, чтобы список сам обновлялся)
    fun getHistory(): Flow<List<ScanHistoryEntity>> = historyDao.getAllHistory()

    // 3. Очистить
    suspend fun clearHistory() = historyDao.clearHistory()
}