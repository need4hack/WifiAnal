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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

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
    private val deviceNamesCache = ConcurrentHashMap<String, String>()
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
        val ssidRaw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // На Android 10+ нужен этот метод, и он возвращает "<unknown ssid>",
            // если у приложения нет разрешения на FINE_LOCATION.
            // Но мы попробуем, это лучше, чем ничего.
            wifiManager.connectionInfo.ssid
        } else {
            wifiManager.connectionInfo.ssid
        }

        // Очищаем SSID от лишних кавычек (e.g. "My_WiFi" -> My_WiFi)
        val cleanSsid = ssidRaw?.removeSurrounding("\"").takeIf { it != "<unknown ssid>" }
            ?: "Wi-Fi (без имени)"

        return CurrentNetworkInfo( cleanSsid, myIpAddress, routerIpAddress)
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
        /*val ssdpJob = CoroutineScope(Dispatchers.IO).launch {
            discoverSSDP()
        }*/

        // 1. Запускаем фоновые слушатели протоколов (SSDP и mDNS)
        // Они будут наполнять cache именами, пока идет перебор IP
        val backgroundJobs = Job()
        val scope = CoroutineScope(Dispatchers.IO + backgroundJobs)

        scope.launch { discoverSSDP() }
        scope.launch { discoverMDNS(myIp) }
        delay(500) //небольшая пауза для того чтобы SSDP успел собрать первые ответы

        // Сканируем не более 20 IP одновременно, чтобы не забить сеть и не потерять пакеты
        val limitConcurrency = Semaphore(20)
        coroutineScope {
            val scanTasks = (1..254).map { i ->
                async(Dispatchers.IO) {
                    limitConcurrency.withPermit {
                        val host = "$subnet.$i"

                        // Не пингуем сами себя
                        if (host == myIp) return@withPermit null

                        if (isHostAlive(host, timeoutMs = 1000)) {
                            // Таймаут 500мс. Если хост не ответил, идем дальше.
                            val name = resolveDeviceName(host)
                            return@withPermit FoundDevice(host, name)
                        }
                        return@withPermit null // Игнорируем недостижимые хосты
                    }
                }
            }
            scanTasks.forEach { task ->
                val device = task.await()
                if(device != null){
                    emit(device)
                    // Игнорируем недостижимые хосты
                }
            }
        }
        backgroundJobs.cancel()
    }.flowOn(Dispatchers.IO) // ВАЖНО: вся работа с сетью - в фоновом потоке

    //Методы определения имени устройства
    private suspend fun resolveDeviceName(ip: String): String {
        //проверка ответило ли устройство по SSDP
        if (deviceNamesCache.containsKey(ip)) {
            return deviceNamesCache[ip]!!
        }
        return withContext(Dispatchers.IO) {
            //netBIOS-эффективно для ПК
            val netBiosName = getNetBiosName(ip)
            if (netBiosName != null) return@withContext netBiosName

            //mDNS - устройства Apple, IoT
            /*val mdnsName = getMdnsName(ip)
            if (mdnsName != null) return@withContext mdnsName*/

            //Http заголовок(роутер, камеры и т.д)
            val http = getHttpTitle(ip)
            if (http != null) return@withContext http

            //Обычный стандартный DNS
            try {
                val inetAddr = InetAddress.getByName(ip)
                val host = inetAddr.canonicalHostName
                if (host != ip) return@withContext host// Если имя совпадает с ip значит не нашли
            } catch (e: Exception) {
            }
            return@withContext ip
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
    private fun discoverMDNS(myLocalIp: String) {
        var jmdns: JmDNS? = null
        try {
            val addr = InetAddress.getByName(myLocalIp)
            jmdns = JmDNS.create(addr, "Scanner")

            // Добавляем слушателя для всех сервисов
            jmdns.addServiceTypeListener(object : javax.jmdns.ServiceTypeListener {
                override fun serviceTypeAdded(event: javax.jmdns.ServiceEvent?) {
                    // Когда найден тип сервиса, просим найти сами сервисы
                    event?.type?.let { type ->
                        jmdns.addServiceListener(type, object : javax.jmdns.ServiceListener {
                            override fun serviceAdded(event: javax.jmdns.ServiceEvent?) {}
                            override fun serviceRemoved(event: javax.jmdns.ServiceEvent?) {}
                            override fun serviceResolved(event: javax.jmdns.ServiceEvent?) {
                                event?.info?.let { info ->
                                    // Сохраняем имя устройства для всех его IP адресов
                                    val name = info.name ?: info.server
                                    info.inet4Addresses.forEach { ipAddr ->
                                        deviceNamesCache[ipAddr.hostAddress] = name
                                    }
                                }
                            }
                        })
                    }
                }
                override fun subTypeForServiceTypeAdded(event: javax.jmdns.ServiceEvent?) {}
            })
            // Ждем пока соберется инфо (цикл жизни корутины)
            Thread.sleep(15000)
        } catch (e: Exception) {
            // Ошибка mDNS
        } finally {
            jmdns?.close()
        }
    }

    //UPnP/SSDP Discovery
    private fun discoverSSDP(){
        var socket: DatagramSocket? = null
        try{
            socket = DatagramSocket()
            socket.soTimeout = 4000 //ожидание ответа
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
                val ip = receivePacket.address.hostAddress

                //Парсим имя ои ответа
                var name = parseHeader(response, "SERVER") /*?: "UPnP Device"*/
                if (name == null) name = parseHeader(response, "USN")

                if(ip != null){
                    val cleanName = name?.substringBefore("/")?.trim() ?: "UPnP Device"
                    deviceNamesCache[ip] = cleanName
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
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()
        return try{
            val req = Request.Builder().url("http://$ip").build()
            client.newCall(req).execute().use {resp ->
                    val body = resp.body?.string() ?: ""
                    "<title>(.*?)</title>".toRegex(RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()
                }
            } catch (e: Exception){
            null
        }
    }

    //что то типо парсера
    private fun parseHeader(response: String, header: String): String? {
        return response.lines().find { it.startsWith(header, true) }?.substringAfter(":")?.trim()
    }

    //проверка доступности хоста
    private fun isHostAlive(host: String, timeoutMs: Int): Boolean {
        try {
            val addr = InetAddress.getByName(host)
            // 1. Быстрый ICMP (если есть права/рут)
            if (addr.isReachable(timeoutMs/2)) return true

            // 2. Если пинг закрыт, пробуем TCP Connect на популярных портах
            // Порты: 80 (Web), 445 (SMB), 135 (RPC), 22 (SSH)
            val ports = listOf(80, 445, 62078, 22, 8080, 53, 554) // 62078 часто открыт на iPhone
            for (port in ports) {
                try {
                    Socket().use {
                        it.connect(InetSocketAddress(host, port), timeoutMs/ports.size)
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