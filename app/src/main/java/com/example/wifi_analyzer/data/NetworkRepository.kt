package com.example.wifi_analyzer.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import android.net.wifi.WifiInfo
import com.example.wifi_analyzer.database.AppDatabase
import com.example.wifi_analyzer.database.ScanHistoryEntity

// Модель для информации о текущем подключении
data class CurrentNetworkInfo(
    val ssid: String = "Не подключено",
    val myIp: String = "?.?.?.?",
    val routerIp: String = "?.?.?.?"
)

// Модель для найденного устройства
data class FoundDevice(
    val ip: String,
    val hostname: String,
    val mac: String = "??:??:??:??:??:??",
    val vendor: String = "Unknown"
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

    private val vendorMap = mapOf(
        "005056" to "VMware",
        "525400" to "QEMU/KVM (Emulator)",
        "001A11" to "Google",
        "3C5C48" to "Google Pixel",
        "F8FFC2" to "Apple",
        "FC9435" to "Samsung"
    )

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

        for (i in 1..254) {
            val host = "$subnet.$i"

            // Не пингуем сами себя
            if (host == myIp) continue

            try {
                val address = InetAddress.getByName(host)
                // Таймаут 500мс. Если хост не ответил, идем дальше.
                if (address.isReachable(500)) {
                    val hostname = address.canonicalHostName ?: host
                    emit(FoundDevice(ip = host, hostname = hostname))
                }
            } catch (e: Exception) {
                // Игнорируем недостижимые хосты
            }
        }
    }.flowOn(Dispatchers.IO) // ВАЖНО: вся работа с сетью - в фоновом потоке

    @SuppressLint("MissingPermission") // Разрешения проверяются в UI
    fun scanWifiDirectDevices(): Flow<FoundDevice> = callbackFlow {
        val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        val channel = p2pManager?.initialize(context, context.mainLooper, null)

        if (p2pManager == null || channel == null) {
            close()
            return@callbackFlow
        }

        // 1. Создаем ресивер, который будет слушать ответы системы
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Система нашла устройства! Запрашиваем список.
                        p2pManager.requestPeers(channel) { peersList ->
                            for (device in peersList.deviceList) {
                                // ВОТ ОНО! device.deviceAddress - это реальный MAC
                                val realMac = device.deviceAddress.uppercase()
                                val deviceName = device.deviceName ?: "Unknown P2P Device"
                                val vendor = getVendorFromMac(realMac)

                                // IP мы не знаем, так как это P2P обнаружение, а не LAN подключение
                                // Но зато у нас есть MAC!
                                trySend(FoundDevice(
                                    ip = "P2P Discovery", // Маркер, что это найдено через Direct
                                    hostname = deviceName,
                                    mac = realMac,
                                    vendor = vendor
                                ))
                            }
                        }
                    }
                }
            }
        }

        // 2. Регистрируем ресивер
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)

        // 3. Запускаем поиск (Discover Peers)
        p2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Скан начался успешно
            }
            override fun onFailure(reason: Int) {
                // Не удалось запустить скан
            }
        })

        // 4. При закрытии потока (уход с экрана) убираем мусор
        awaitClose {
            try {
                context.unregisterReceiver(receiver)
                p2pManager.stopPeerDiscovery(channel, null)
            } catch (e: Exception) {}
        }
    }

    private fun getVendorFromMac(mac: String): String {
        if (mac.length < 8) return "Unknown"
        val oui = mac.substring(0, 8).replace(":", "").uppercase()
        return vendorMap[oui] ?: "Unknown Vendor"
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

    // Инициализация DAO
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