package com.kyilmaz.m3eshield

import android.net.VpnService
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class DnsForwarder(
    private val vpnService: VpnService,
    private val vpnFileDescriptor: java.io.FileDescriptor
) : Runnable {

    private var isRunning = false
    private val socket = DatagramSocket()
    // Map from DatagramPacket local port to the original request's source IP and source Port
    private val pendingRequests = ConcurrentHashMap<Int, Pair<Int, Int>>()

    fun start() {
        isRunning = true
        vpnService.protect(socket)
        Thread(this, "VpnReadThread").start()
        Thread(::receiveFromSocket, "UdpReceiveThread").start()
    }

    fun stop() {
        isRunning = false
        socket.close()
    }

    override fun run() {
        val inputStream = FileInputStream(vpnFileDescriptor)
        val packet = ByteArray(32767)

        while (isRunning) {
            try {
                val length = inputStream.read(packet)
                if (length > 0) {
                    handleRawPacket(packet, length)
                }
            } catch (e: Exception) {
                if (isRunning) e.printStackTrace()
            }
        }
    }

    private fun handleRawPacket(packet: ByteArray, length: Int) {
        val buffer = ByteBuffer.wrap(packet, 0, length)
        val versionAndIHL = buffer.get().toInt() and 0xFF
        val version = versionAndIHL shr 4
        if (version != 4) return // Only support IPv4 for simplicity

        val ihl = versionAndIHL and 0x0F
        val ipHeaderLen = ihl * 4

        buffer.position(9)
        val protocol = buffer.get().toInt() and 0xFF
        if (protocol != 17) return // Only support UDP

        buffer.position(12)
        val srcIp = buffer.int
        val dstIp = buffer.int

        buffer.position(ipHeaderLen)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF
        val udpLength = buffer.short.toInt() and 0xFFFF
        // Skip UDP checksum
        buffer.short 

        val payloadLength = udpLength - 8
        if (payloadLength <= 0 || buffer.position() + payloadLength > length) return

        val payload = ByteArray(payloadLength)
        buffer.get(payload)

        // Only forward DNS requests (port 53)
        if (dstPort == 53) {
            forwardDns(srcIp, dstIp, srcPort, dstPort, payload)
        }
    }

    private fun forwardDns(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, payload: ByteArray) {
        try {
            val destAddress = InetAddress.getByAddress(intToByteArray(dstIp))
            val datagramPacket = DatagramPacket(payload, payload.size, destAddress, dstPort)

            // We use the srcPort to map the reply back. 
            // In a real NAT we'd allocate a new port, but assuming one user, we can try to use a dummy or the original.
            // Since we use a single socket, we need to map the DNS transaction ID or just a port.
            // For simplicity, we use the DNS Transaction ID (first 2 bytes of payload)
            if (payload.size >= 2) {
                val txId = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                pendingRequests[txId] = Pair(srcIp, srcPort)
            }

            socket.send(datagramPacket)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun receiveFromSocket() {
        val outStream = FileOutputStream(vpnFileDescriptor)
        val buffer = ByteArray(32767)
        val packet = DatagramPacket(buffer, buffer.size)

        while (isRunning) {
            try {
                packet.length = buffer.size
                socket.receive(packet)
                val txId = if (packet.length >= 2) {
                    ((packet.data[packet.offset].toInt() and 0xFF) shl 8) or (packet.data[packet.offset + 1].toInt() and 0xFF)
                } else -1

                val originalClient = pendingRequests.remove(txId)
                if (originalClient != null) {
                    val (clientIp, clientPort) = originalClient
                    
                    val dstIp = clientIp
                    val dstPort = clientPort
                    
                    val serverIp = byteArrayToInt(packet.address.address)
                    val serverPort = packet.port

                    val replyPacket = buildIpUdpPacket(serverIp, dstIp, serverPort, dstPort, packet.data, packet.offset, packet.length)
                    outStream.write(replyPacket)
                }
            } catch (e: Exception) {
                if (isRunning) e.printStackTrace()
            }
        }
    }

    private fun buildIpUdpPacket(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, payload: ByteArray, offset: Int, length: Int): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + length

        val buffer = ByteBuffer.allocate(totalLen)
        
        // IPv4 Header
        buffer.put((4 shl 4 or 5).toByte()) // Version 4, IHL 5
        buffer.put(0.toByte()) // TOS
        buffer.putShort(totalLen.toShort()) // Total Length
        buffer.putShort(0.toShort()) // Identification
        buffer.putShort(0.toShort()) // Flags & Fragment Offset
        buffer.put(64.toByte()) // TTL
        buffer.put(17.toByte()) // Protocol (UDP)
        buffer.putShort(0.toShort()) // Checksum (calculate later)
        buffer.putInt(srcIp) // Source IP
        buffer.putInt(dstIp) // Destination IP

        // UDP Header
        buffer.putShort(srcPort.toShort()) // Source Port
        buffer.putShort(dstPort.toShort()) // Destination Port
        buffer.putShort((udpHeaderLen + length).toShort()) // UDP Length
        buffer.putShort(0.toShort()) // UDP Checksum (0 = disabled)

        // Payload
        buffer.put(payload, offset, length)

        val packet = buffer.array()

        // Calculate IP Checksum
        val ipChecksum = computeChecksum(packet, 0, ipHeaderLen)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        return packet
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    private fun byteArrayToInt(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0xFF) shl 24) or
               ((bytes[1].toInt() and 0xFF) shl 16) or
               ((bytes[2].toInt() and 0xFF) shl 8) or
               (bytes[3].toInt() and 0xFF)
    }
}