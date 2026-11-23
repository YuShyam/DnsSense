package com.yushyam.dnssense.engine

import com.yushyam.dnssense.data.model.HostnameValidator
import com.yushyam.dnssense.data.model.ProbeResult
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * RFC 7858 DNS-over-TLS (TCP 853) 連線測試器 (Active DoT Prober)
 */
object DotProber {

    private const val DOT_PORT = 853
    private const val DEFAULT_TIMEOUT_MS = 2500
    private const val PROBE_QNAME = "connectivitycheck.gstatic.com"

    fun probe(
        hostname: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): ProbeResult {
        val cleanHost = hostname.trim()
        if (!HostnameValidator.isValid(cleanHost)) {
            return ProbeResult(false, 0L, "私人 DNS 位址格式無效")
        }

        val startNs = System.nanoTime()
        var socket: SSLSocket? = null

        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            socket = factory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(cleanHost, DOT_PORT), timeoutMs)
            socket.soTimeout = timeoutMs

            val sslParams = SSLParameters().apply {
                serverNames = listOf(SNIHostName(cleanHost))
            }
            socket.sslParameters = sslParams
            socket.startHandshake()

            val verified = HttpsURLConnection.getDefaultHostnameVerifier()
                .verify(cleanHost, socket.session)
            if (!verified) {
                return ProbeResult(false, elapsedMs(startNs), "TLS 憑證名稱不符（可能被網路攔截）")
            }

            val txId = Random.nextInt(1, 0xFFFF)
            val dnsQuery = buildDnsAQuery(txId, PROBE_QNAME)
            val output = DataOutputStream(socket.outputStream)
            output.writeShort(dnsQuery.size)
            output.write(dnsQuery)
            output.flush()

            val input = DataInputStream(socket.inputStream)
            val respLen = input.readUnsignedShort()
            if (respLen < 12 || respLen > 4096) {
                return ProbeResult(false, elapsedMs(startNs), "DNS 回應封包長度異常 ($respLen)")
            }
            val resp = ByteArray(respLen)
            input.readFully(resp)

            val respTxId = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
            val flags = ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF)
            val isResponse = (flags and 0x8000) != 0
            val rcode = flags and 0x000F
            val latency = elapsedMs(startNs)

            if (respTxId == txId && isResponse && rcode == 0) {
                ProbeResult(true, latency, "853 埠連線正常 (${latency}ms)")
            } else {
                ProbeResult(false, latency, "DNS 伺服器回傳錯誤 (RCODE=$rcode)")
            }
        } catch (_: SocketTimeoutException) {
            ProbeResult(false, elapsedMs(startNs), "853 埠連線逾時（可能被目前網路阻擋）")
        } catch (e: javax.net.ssl.SSLException) {
            ProbeResult(false, elapsedMs(startNs), "TLS 連線失敗：${e.message ?: "連線被阻斷"}")
        } catch (e: Exception) {
            ProbeResult(false, elapsedMs(startNs), "連線測試失敗：${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    internal fun buildDnsAQuery(txId: Int, domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((txId shr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)

        for (label in domain.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)

        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L
}
