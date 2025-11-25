package com.example.wifi_analyzer.data

import java.net.InetSocketAddress
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
}