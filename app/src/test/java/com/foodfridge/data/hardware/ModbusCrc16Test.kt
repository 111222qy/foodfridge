package com.foodfridge.data.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class ModbusCrc16Test {

    @Test
    fun `empty frame returns initial CRC value`() {
        val crc = modbusCrc16(byteArrayOf(), 0, 0)
        assertEquals(0xFFFF, crc)
    }

    @Test
    fun `known Modbus RTU frame - read holding registers slave 0x01 addr 0x0000 len 2`() {
        // 标准 Modbus 测试帧: 01 03 00 00 00 02, CRC = 0x0BC4（低字节先传 = C4 0B）
        val frame = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x02)
        val crc = modbusCrc16(frame, 0, frame.size)
        assertEquals(0x0BC4, crc)
    }

    @Test
    fun `known Modbus RTU frame - slave 0xFF addr 0x0002`() {
        // 项目实际使用的帧: FF 03 00 00 00 02，预先计算正确的 CRC 值
        val frame = byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x00, 0x00, 0x02)
        val crc = modbusCrc16(frame, 0, frame.size)
        // CRC = 0xD5D1，在线缆上按低字节优先发送为 D1 D5。
        assertEquals(0xD5D1, crc)
    }

    @Test
    fun `offset and length are respected`() {
        // 同样的帧数据但放在偏移位置
        val padded = byteArrayOf(0x00, 0x00, 0x01, 0x03, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00)
        val crc = modbusCrc16(padded, 2, 6)
        assertEquals(0x0BC4, crc)
    }

    @Test
    fun `single byte`() {
        // 单字节 0x01: 从 0xFFFF 开始，经过 8 次迭代
        val crc = modbusCrc16(byteArrayOf(0x01), 0, 1)
        // 手动推算或在线工具: 0x807E
        assertEquals(0x807E, crc)
    }

    @Test
    fun `CRC changes when data changes`() {
        val frame1 = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x02)
        val frame2 = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x03)  // 最后一个字节变了
        val crc1 = modbusCrc16(frame1, 0, frame1.size)
        val crc2 = modbusCrc16(frame2, 0, frame2.size)
        assert(crc1 != crc2) { "CRC should differ when input differs" }
    }
}
