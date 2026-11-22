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
 * RFC 7858 DNS-over-TLS (TCP 853) 真實查詢存活探測器 (Active DoT Prober)
 *
 * 不僅檢查 TCP 853 埠是否可連線，而是完整執行 TLS 握手、SNI 憑證主機名稱校驗，
 * 並送出標準 DNS A 記錄查詢封包驗證伺服器回應 NOERROR，精準排除防火牆 DPI 靜默丟包。
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
            return ProbeResult(false, 0L, "無效的私人 DNS 主機名稱")
        }

        val startNs = System.nanoTime()
        var socket: SSLSocket? = null

        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            socket = factory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(cleanHost, DOT_PORT), timeoutMs)
            socket.soTimeout = timeoutMs

            // 強制設定 SNI 並執行標準 TLS 握手與主機憑證驗證
            val sslParams = SSLParameters().apply {
                serverNames = listOf(SNIHostName(cleanHost))
            }
            socket.sslParameters = sslParams
            socket.startHandshake()

            val verified = HttpsURLConnection.getDefaultHostnameVerifier()
                .verify(cleanHost, socket.session)
            if (!verified) {
                return ProbeResult(false, elapsedMs(startNs), "TLS 憑證主機名稱不符（可能遭遇攔截）")
            }

            // 構造並發送 RFC 7858 2-byte 長度前綴 DNS Query
            val txId = Random.nextInt(1, 0xFFFF)
            val dnsQuery = buildDnsAQuery(txId, PROBE_QNAME)
            val output = DataOutputStream(socket.outputStream)
            output.writeShort(dnsQuery.size)
            output.write(dnsQuery)
            output.flush()

            // 讀取 DNS 回應並校驗 Header (Transaction ID + QR=1 + RCODE=0)
            val input = DataInputStream(socket.inputStream)
            val respLen = input.readUnsignedShort()
            if (respLen < 12 || respLen > 4096) {
                return ProbeResult(false, elapsedMs(startNs), "DoT 回應封包長度異常 ($respLen)")
            }
            val resp = ByteArray(respLen)
            input.readFully(resp)

            val respTxId = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
            val flags = ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF)
            val isResponse = (flags and 0x8000) != 0
            val rcode = flags and 0x000F
            val latency = elapsedMs(startNs)

            if (respTxId == txId && isResponse && rcode == 0) {
                ProbeResult(true, latency, "DoT 853 連線正常 (${latency}ms)")
            } else {
                ProbeResult(false, latency, "DoT 伺服器回傳異常狀態 (RCODE=$rcode)")
            }
        } catch (_: SocketTimeoutException) {
            ProbeResult(false, elapsedMs(startNs), "TCP 853 連線或查詢逾時（疑似遭防火牆封鎖）")
        } catch (e: javax.net.ssl.SSLException) {
            ProbeResult(false, elapsedMs(startNs), "TLS 握手失敗：${e.message ?: "憑證或連線遭阻斷"}")
        } catch (e: Exception) {
            ProbeResult(false, elapsedMs(startNs), "探測失敗：${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    /**
     * 建構最小標準 DNS A 記錄查詢封包 (RFC 1035)
     * Builds a minimal standard DNS IN A query packet.
     */
    internal fun buildDnsAQuery(txId: Int, domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        // Header: ID, Flags (RD=1 -> 0x0100), QDCOUNT=1, AN/NS/AR=0
        out.write((txId shr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)

        // QNAME labels
        for (label in domain.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00) // root label terminator

        // QTYPE = A (1), QCLASS = IN (1)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L
}
