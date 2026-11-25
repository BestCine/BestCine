package com.nuvio.app.bestcine.cast

/**
 * Ferramentas puras em Kotlin Multiplatform (100% compartilhadas entre Android, Windows, macOS,
 * Linux e iOS/iPadOS) para streams MPEG-TS (188 bytes/pacote) usados no DLNA:
 * 1) [MpegTsNormalizer]: corrige Continuity Counter (CC) e mantém PTS/DTS/PCR monotônicos
 *    através de cortes de comercial (#EXT-X-DISCONTINUITY) e reconexões de servidores IPTV,
 *    impedindo que TVs Samsung/LG encerrem a reprodução após alguns minutos.
 * 2) [muxVideoAndAudioSegments]: combina segmentos HLS de vídeo-only + áudio separado
 *    (#EXT-X-MEDIA:TYPE=AUDIO, como na Pluto TV) em um único fluxo `video/mp2t` com
 *    PAT/PMT unificados (CRC-32 MPEG-2 válido), entregando áudio 100% nativo na Smart TV!
 */
internal object BestCineMpegTsTools {
    const val PACKET_SIZE = 188
    const val SYNC_BYTE: Byte = 0x47
    const val PAT_PID = 0x0000
    const val UNIFIED_PMT_PID = 0x1000
    const val DEFAULT_VIDEO_PID = 0x0100
    const val DEFAULT_AUDIO_PID = 0x0101
    private const val PTS_MODULO = 1L shl 33 // 8589934592 (90 kHz, ~26.5h)
    private const val PTS_MASK = PTS_MODULO - 1L

    /**
     * Pacote MPEG-TS NULL padrão ISO/IEC 13818-1 (PID 0x1FFF, 188 bytes).
     * Ignorado pelos decodificadores das Smart TVs, mas mantém a conexão TCP e o
     * demuxer ativos durante breves reconexões/oscilações de canais Ao Vivo.
     */
    val NULL_PACKET: ByteArray = ByteArray(PACKET_SIZE) { 0xFF.toByte() }.apply {
        this[0] = SYNC_BYTE
        this[1] = 0x1F.toByte()
        this[2] = 0xFF.toByte()
        this[3] = 0x10.toByte()
    }

    private class GrowableByteBuffer(initialCapacity: Int = 4096) {
        private var buf = ByteArray(initialCapacity.coerceAtLeast(256))
        var size: Int = 0
            private set

        fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            if (length <= 0) return
            ensureCapacity(size + length)
            data.copyInto(buf, destinationOffset = size, startIndex = offset, endIndex = offset + length)
            size += length
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensureCapacity(minCap: Int) {
            if (minCap <= buf.size) return
            var newCap = buf.size * 2
            while (newCap < minCap) newCap *= 2
            buf = buf.copyOf(newCap)
        }
    }

    fun mpeg2Crc32(data: ByteArray, offset: Int, length: Int): Int {
        var crc = -1 // 0xFFFFFFFF
        for (i in offset until (offset + length)) {
            val b = data[i].toInt() and 0xFF
            crc = crc xor (b shl 24)
            repeat(8) {
                crc = if ((crc and Int.MIN_VALUE) != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }

    fun buildPatPacket(pmtPid: Int = UNIFIED_PMT_PID, continuityCounter: Int = 0): ByteArray {
        val pkt = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
        pkt[0] = SYNC_BYTE
        pkt[1] = 0x40.toByte() // PUSI = 1, PID = 0x0000
        pkt[2] = 0x00
        pkt[3] = (0x10 or (continuityCounter and 0x0F)).toByte() // payload only
        pkt[4] = 0x00 // pointer_field

        val secStart = 5
        pkt[secStart + 0] = 0x00 // table_id = PAT
        val sectionLen = 13 // 5 header + 4 program entry + 4 CRC
        pkt[secStart + 1] = (0xB0 or ((sectionLen ushr 8) and 0x0F)).toByte()
        pkt[secStart + 2] = (sectionLen and 0xFF).toByte()
        pkt[secStart + 3] = 0x00 // transport_stream_id high
        pkt[secStart + 4] = 0x01 // transport_stream_id low
        pkt[secStart + 5] = 0xC1.toByte() // version 0, current_next = 1
        pkt[secStart + 6] = 0x00 // section_number
        pkt[secStart + 7] = 0x00 // last_section_number
        // Program 1 -> pmtPid
        pkt[secStart + 8] = 0x00
        pkt[secStart + 9] = 0x01
        pkt[secStart + 10] = (0xE0 or ((pmtPid ushr 8) and 0x1F)).toByte()
        pkt[secStart + 11] = (pmtPid and 0xFF).toByte()

        val crc = mpeg2Crc32(pkt, secStart, 12)
        pkt[secStart + 12] = ((crc ushr 24) and 0xFF).toByte()
        pkt[secStart + 13] = ((crc ushr 16) and 0xFF).toByte()
        pkt[secStart + 14] = ((crc ushr 8) and 0xFF).toByte()
        pkt[secStart + 15] = (crc and 0xFF).toByte()
        return pkt
    }

    fun buildPmtPacket(
        pmtPid: Int = UNIFIED_PMT_PID,
        pcrPid: Int,
        videoStreamType: Int,
        videoPid: Int,
        audioStreamType: Int,
        audioPid: Int,
        continuityCounter: Int = 0,
    ): ByteArray {
        val pkt = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
        pkt[0] = SYNC_BYTE
        pkt[1] = (0x40 or ((pmtPid ushr 8) and 0x1F)).toByte()
        pkt[2] = (pmtPid and 0xFF).toByte()
        pkt[3] = (0x10 or (continuityCounter and 0x0F)).toByte()
        pkt[4] = 0x00 // pointer_field

        val secStart = 5
        pkt[secStart + 0] = 0x02 // table_id = PMT
        val sectionLen = 9 + 5 + 5 + 4 // header(9) + video(5) + audio(5) + CRC(4) = 23
        pkt[secStart + 1] = (0xB0 or ((sectionLen ushr 8) and 0x0F)).toByte()
        pkt[secStart + 2] = (sectionLen and 0xFF).toByte()
        pkt[secStart + 3] = 0x00 // program_number = 1
        pkt[secStart + 4] = 0x01
        pkt[secStart + 5] = 0xC1.toByte() // version 0, current_next = 1
        pkt[secStart + 6] = 0x00
        pkt[secStart + 7] = 0x00
        pkt[secStart + 8] = (0xE0 or ((pcrPid ushr 8) and 0x1F)).toByte()
        pkt[secStart + 9] = (pcrPid and 0xFF).toByte()
        pkt[secStart + 10] = 0xF0.toByte() // program_info_length = 0
        pkt[secStart + 11] = 0x00

        // Elementary stream 1: Video
        pkt[secStart + 12] = (videoStreamType and 0xFF).toByte()
        pkt[secStart + 13] = (0xE0 or ((videoPid ushr 8) and 0x1F)).toByte()
        pkt[secStart + 14] = (videoPid and 0xFF).toByte()
        pkt[secStart + 15] = 0xF0.toByte()
        pkt[secStart + 16] = 0x00

        // Elementary stream 2: Audio
        pkt[secStart + 17] = (audioStreamType and 0xFF).toByte()
        pkt[secStart + 18] = (0xE0 or ((audioPid ushr 8) and 0x1F)).toByte()
        pkt[secStart + 19] = (audioPid and 0xFF).toByte()
        pkt[secStart + 20] = 0xF0.toByte()
        pkt[secStart + 21] = 0x00

        val crc = mpeg2Crc32(pkt, secStart, 22)
        pkt[secStart + 22] = ((crc ushr 24) and 0xFF).toByte()
        pkt[secStart + 23] = ((crc ushr 16) and 0xFF).toByte()
        pkt[secStart + 24] = ((crc ushr 8) and 0xFF).toByte()
        pkt[secStart + 25] = (crc and 0xFF).toByte()
        return pkt
    }

    data class TrackInfo(
        val pmtPid: Int,
        val pcrPid: Int,
        val videoPid: Int?,
        val videoStreamType: Int,
        val audioPid: Int?,
        val audioStreamType: Int,
    )

    fun inspectTrackInfo(tsData: ByteArray): TrackInfo? {
        val start = findSyncOffset(tsData)
        if (start < 0) return null

        var pmtPid = -1
        var offset = start
        val maxScan = minOf(tsData.size - PACKET_SIZE, start + PACKET_SIZE * 256)
        while (offset <= maxScan) {
            if (tsData[offset] != SYNC_BYTE) {
                offset += 1
                continue
            }
            val pid = ((tsData[offset + 1].toInt() and 0x1F) shl 8) or (tsData[offset + 2].toInt() and 0xFF)
            val pusi = (tsData[offset + 1].toInt() and 0x40) != 0
            val payloadPos = payloadOffset(tsData, offset)
            if (pid == PAT_PID && pusi && payloadPos in (offset + 4)..(offset + PACKET_SIZE - 16)) {
                val ptr = tsData[payloadPos].toInt() and 0xFF
                val sec = payloadPos + 1 + ptr
                if (sec + 12 <= offset + PACKET_SIZE && (tsData[sec].toInt() and 0xFF) == 0x00) {
                    val secLen = ((tsData[sec + 1].toInt() and 0x0F) shl 8) or (tsData[sec + 2].toInt() and 0xFF)
                    var pos = sec + 8
                    val end = minOf(sec + 3 + secLen - 4, offset + PACKET_SIZE)
                    while (pos + 4 <= end) {
                        val progNum = ((tsData[pos].toInt() and 0xFF) shl 8) or (tsData[pos + 1].toInt() and 0xFF)
                        val mappedPid = ((tsData[pos + 2].toInt() and 0x1F) shl 8) or (tsData[pos + 3].toInt() and 0xFF)
                        if (progNum != 0) {
                            pmtPid = mappedPid
                            break
                        }
                        pos += 4
                    }
                }
            } else if (pmtPid > 0 && pid == pmtPid && pusi && payloadPos in (offset + 4)..(offset + PACKET_SIZE - 16)) {
                val ptr = tsData[payloadPos].toInt() and 0xFF
                val sec = payloadPos + 1 + ptr
                if (sec + 12 <= offset + PACKET_SIZE && (tsData[sec].toInt() and 0xFF) == 0x02) {
                    val secLen = ((tsData[sec + 1].toInt() and 0x0F) shl 8) or (tsData[sec + 2].toInt() and 0xFF)
                    val pcrPid = ((tsData[sec + 8].toInt() and 0x1F) shl 8) or (tsData[sec + 9].toInt() and 0xFF)
                    val progInfoLen = ((tsData[sec + 10].toInt() and 0x0F) shl 8) or (tsData[sec + 11].toInt() and 0xFF)
                    var pos = sec + 12 + progInfoLen
                    val end = minOf(sec + 3 + secLen - 4, offset + PACKET_SIZE)
                    var videoPid: Int? = null
                    var videoType = 0x1B // H.264
                    var audioPid: Int? = null
                    var audioType = 0x0F // AAC ADTS
                    while (pos + 5 <= end) {
                        val sType = tsData[pos].toInt() and 0xFF
                        val ePid = ((tsData[pos + 1].toInt() and 0x1F) shl 8) or (tsData[pos + 2].toInt() and 0xFF)
                        val esInfoLen = ((tsData[pos + 3].toInt() and 0x0F) shl 8) or (tsData[pos + 4].toInt() and 0xFF)
                        when (sType) {
                            0x1B, 0x24, 0x02, 0x01, 0x10 -> {
                                if (videoPid == null) {
                                    videoPid = ePid
                                    videoType = sType
                                }
                            }
                            0x0F, 0x11, 0x03, 0x04, 0x81, 0x87 -> {
                                if (audioPid == null) {
                                    audioPid = ePid
                                    audioType = sType
                                }
                            }
                        }
                        pos += 5 + esInfoLen
                    }
                    return TrackInfo(
                        pmtPid = pmtPid,
                        pcrPid = pcrPid,
                        videoPid = videoPid,
                        videoStreamType = videoType,
                        audioPid = audioPid,
                        audioStreamType = audioType,
                    )
                }
            }
            offset += PACKET_SIZE
        }
        return null
    }

    fun muxVideoAndAudioSegments(videoBytes: ByteArray, audioBytes: ByteArray): ByteArray {
        val vStart = findSyncOffset(videoBytes)
        if (vStart < 0) return videoBytes

        val vInfo = inspectTrackInfo(videoBytes)
        val videoPmtPid = vInfo?.pmtPid ?: -1
        val videoPid = vInfo?.videoPid ?: DEFAULT_VIDEO_PID
        val videoType = vInfo?.videoStreamType ?: 0x1B
        val pcrPid = vInfo?.pcrPid?.takeIf { it in 1..0x1FFE } ?: videoPid

        val aStart = findSyncOffset(audioBytes)
        val audioIsTs = aStart >= 0

        val out = GrowableByteBuffer(videoBytes.size + audioBytes.size + 2048)

        if (audioIsTs) {
            val aInfo = inspectTrackInfo(audioBytes)
            val audioPmtPid = aInfo?.pmtPid ?: -1
            val srcAudioPid = aInfo?.audioPid ?: guessFirstNonPsiPid(audioBytes, aStart, audioPmtPid) ?: DEFAULT_AUDIO_PID
            val audioType = aInfo?.audioStreamType ?: 0x0F
            val dstAudioPid = if (srcAudioPid == videoPid || srcAudioPid == UNIFIED_PMT_PID) {
                if (videoPid != DEFAULT_AUDIO_PID) DEFAULT_AUDIO_PID else 0x0102
            } else {
                srcAudioPid
            }

            val videoPkts = extractElementaryPackets(videoBytes, vStart, skipPids = setOf(PAT_PID, videoPmtPid))
            val audioPkts = extractElementaryPackets(
                audioBytes,
                aStart,
                skipPids = setOf(PAT_PID, audioPmtPid),
                onlyPid = srcAudioPid,
                remapToPid = dstAudioPid,
            )

            if (audioPkts.isEmpty()) return videoBytes

            out.write(buildPatPacket(UNIFIED_PMT_PID))
            out.write(buildPmtPacket(UNIFIED_PMT_PID, pcrPid, videoType, videoPid, audioType, dstAudioPid))

            val totalV = videoPkts.size
            val totalA = audioPkts.size
            var vi = 0
            var ai = 0
            var emitted = 0
            while (vi < totalV || ai < totalA) {
                val wantAudio = ai < totalA && (vi >= totalV || (ai.toLong() * totalV <= vi.toLong() * totalA))
                if (wantAudio) {
                    out.write(audioPkts[ai++])
                } else {
                    out.write(videoPkts[vi++])
                }
                emitted += 1
                if (emitted % 400 == 0) {
                    out.write(buildPatPacket(UNIFIED_PMT_PID))
                    out.write(buildPmtPacket(UNIFIED_PMT_PID, pcrPid, videoType, videoPid, audioType, dstAudioPid))
                }
            }
            return out.toByteArray()
        }

        val adtsStart = findAdtsSyncOffset(audioBytes)
        if (adtsStart < 0) return videoBytes
        val dstAudioPid = if (videoPid != DEFAULT_AUDIO_PID) DEFAULT_AUDIO_PID else 0x0102
        val firstVideoPts = findFirstPts(videoBytes, vStart) ?: 0L
        val audioPkts = packetizeAdtsToTs(audioBytes, adtsStart, dstAudioPid, firstVideoPts)
        if (audioPkts.isEmpty()) return videoBytes

        val videoPkts = extractElementaryPackets(videoBytes, vStart, skipPids = setOf(PAT_PID, videoPmtPid))
        out.write(buildPatPacket(UNIFIED_PMT_PID))
        out.write(buildPmtPacket(UNIFIED_PMT_PID, pcrPid, videoType, videoPid, 0x0F, dstAudioPid))
        val totalV = videoPkts.size
        val totalA = audioPkts.size
        var vi = 0
        var ai = 0
        while (vi < totalV || ai < totalA) {
            val wantAudio = ai < totalA && (vi >= totalV || (ai.toLong() * totalV <= vi.toLong() * totalA))
            if (wantAudio) out.write(audioPkts[ai++]) else out.write(videoPkts[vi++])
        }
        return out.toByteArray()
    }

    private fun extractElementaryPackets(
        data: ByteArray,
        startOffset: Int,
        skipPids: Set<Int>,
        onlyPid: Int? = null,
        remapToPid: Int? = null,
    ): List<ByteArray> {
        val result = ArrayList<ByteArray>(data.size / PACKET_SIZE)
        var off = startOffset
        while (off + PACKET_SIZE <= data.size) {
            if (data[off] != SYNC_BYTE) {
                off += 1
                continue
            }
            val pid = ((data[off + 1].toInt() and 0x1F) shl 8) or (data[off + 2].toInt() and 0xFF)
            if (pid !in skipPids && pid != 0x1FFF && (onlyPid == null || pid == onlyPid)) {
                val copy = data.copyOfRange(off, off + PACKET_SIZE)
                if (remapToPid != null && remapToPid != pid) {
                    copy[1] = ((copy[1].toInt() and 0xE0) or ((remapToPid ushr 8) and 0x1F)).toByte()
                    copy[2] = (remapToPid and 0xFF).toByte()
                }
                result.add(copy)
            }
            off += PACKET_SIZE
        }
        return result
    }

    private fun guessFirstNonPsiPid(data: ByteArray, startOffset: Int, pmtPid: Int): Int? {
        var off = startOffset
        val limit = minOf(data.size - PACKET_SIZE, startOffset + PACKET_SIZE * 64)
        while (off <= limit) {
            if (data[off] != SYNC_BYTE) {
                off += 1
                continue
            }
            val pid = ((data[off + 1].toInt() and 0x1F) shl 8) or (data[off + 2].toInt() and 0xFF)
            if (pid != PAT_PID && pid != pmtPid && pid != 0x0011 && pid != 0x1FFF) {
                return pid
            }
            off += PACKET_SIZE
        }
        return null
    }

    private fun findAdtsSyncOffset(data: ByteArray): Int {
        var pos = 0
        if (data.size >= 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            val tagSize = ((data[6].toInt() and 0x7F) shl 21) or
                ((data[7].toInt() and 0x7F) shl 14) or
                ((data[8].toInt() and 0x7F) shl 7) or
                (data[9].toInt() and 0x7F)
            pos = 10 + tagSize
        }
        val limit = minOf(data.size - 7, pos + 1024)
        while (pos < limit) {
            if ((data[pos].toInt() and 0xFF) == 0xFF && (data[pos + 1].toInt() and 0xF6) == 0xF0) {
                return pos
            }
            pos += 1
        }
        return -1
    }

    private fun packetizeAdtsToTs(
        adts: ByteArray,
        startOffset: Int,
        audioPid: Int,
        startPts90k: Long,
    ): List<ByteArray> {
        val sampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val result = ArrayList<ByteArray>()
        var pos = startOffset
        var pts = startPts90k and PTS_MASK
        while (pos + 7 <= adts.size) {
            if ((adts[pos].toInt() and 0xFF) != 0xFF || (adts[pos + 1].toInt() and 0xF6) != 0xF0) {
                pos += 1
                continue
            }
            val srIdx = (adts[pos + 2].toInt() ushr 2) and 0x0F
            val sampleRate = sampleRates.getOrElse(srIdx) { 48000 }
            val frameLen = (((adts[pos + 3].toInt() and 0x03) shl 11) or
                ((adts[pos + 4].toInt() and 0xFF) shl 3) or
                ((adts[pos + 5].toInt() ushr 5) and 0x07))
            if (frameLen < 7 || pos + frameLen > adts.size) break

            val pesLen = 8 + frameLen
            val pes = ByteArray(14 + frameLen)
            pes[0] = 0x00
            pes[1] = 0x00
            pes[2] = 0x01
            pes[3] = 0xC0.toByte()
            pes[4] = ((pesLen ushr 8) and 0xFF).toByte()
            pes[5] = (pesLen and 0xFF).toByte()
            pes[6] = 0x80.toByte()
            pes[7] = 0x80.toByte()
            pes[8] = 0x05
            encodePts(pes, 9, pts, prefix = 0x20)
            adts.copyInto(pes, destinationOffset = 14, startIndex = pos, endIndex = pos + frameLen)

            var pOff = 0
            var first = true
            while (pOff < pes.size) {
                val pkt = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
                pkt[0] = SYNC_BYTE
                pkt[1] = ((if (first) 0x40 else 0x00) or ((audioPid ushr 8) and 0x1F)).toByte()
                pkt[2] = (audioPid and 0xFF).toByte()
                val rem = pes.size - pOff
                if (rem >= 184) {
                    pkt[3] = 0x10
                    pes.copyInto(pkt, destinationOffset = 4, startIndex = pOff, endIndex = pOff + 184)
                    pOff += 184
                } else {
                    val pad = 184 - rem
                    pkt[3] = 0x30
                    pkt[4] = (pad - 1).toByte()
                    if (pad > 1) pkt[5] = 0x00
                    pes.copyInto(pkt, destinationOffset = 4 + pad, startIndex = pOff, endIndex = pOff + rem)
                    pOff += rem
                }
                result.add(pkt)
                first = false
            }

            val frameTicks = (1024L * 90_000L) / sampleRate
            pts = (pts + frameTicks) and PTS_MASK
            pos += frameLen
        }
        return result
    }

    private fun findFirstPts(tsData: ByteArray, startOffset: Int): Long? {
        var off = startOffset
        val limit = minOf(tsData.size - PACKET_SIZE, startOffset + PACKET_SIZE * 128)
        while (off <= limit) {
            if (tsData[off] != SYNC_BYTE) {
                off += 1
                continue
            }
            val pusi = (tsData[off + 1].toInt() and 0x40) != 0
            if (pusi) {
                val p = payloadOffset(tsData, off)
                if (p >= 0 && p + 14 <= off + PACKET_SIZE &&
                    tsData[p] == 0.toByte() && tsData[p + 1] == 0.toByte() && tsData[p + 2] == 1.toByte()
                ) {
                    val streamId = tsData[p + 3].toInt() and 0xFF
                    if (streamId in 0xC0..0xEF) {
                        val flags = (tsData[p + 7].toInt() ushr 6) and 0x03
                        if (flags >= 2) return decodePts(tsData, p + 9)
                    }
                }
            }
            off += PACKET_SIZE
        }
        return null
    }

    fun findSyncOffset(data: ByteArray): Int {
        val limit = minOf(data.size - PACKET_SIZE, PACKET_SIZE * 4)
        for (i in 0..limit) {
            if (data[i] == SYNC_BYTE && (i + PACKET_SIZE >= data.size || data[i + PACKET_SIZE] == SYNC_BYTE)) {
                return i
            }
        }
        return -1
    }

    private fun payloadOffset(pkt: ByteArray, base: Int): Int {
        val afc = (pkt[base + 3].toInt() ushr 4) and 0x03
        if ((afc and 0x01) == 0) return -1
        return if ((afc and 0x02) != 0) {
            val adaptLen = pkt[base + 4].toInt() and 0xFF
            val pos = base + 5 + adaptLen
            if (pos < base + PACKET_SIZE) pos else -1
        } else {
            base + 4
        }
    }

    private fun decodePts(buf: ByteArray, off: Int): Long {
        val b0 = (buf[off + 0].toLong() and 0x0E) shl 29
        val b1 = (buf[off + 1].toLong() and 0xFF) shl 22
        val b2 = (buf[off + 2].toLong() and 0xFE) shl 14
        val b3 = (buf[off + 3].toLong() and 0xFF) shl 7
        val b4 = (buf[off + 4].toLong() and 0xFE) ushr 1
        return (b0 or b1 or b2 or b3 or b4) and PTS_MASK
    }

    private fun encodePts(buf: ByteArray, off: Int, pts: Long, prefix: Int) {
        val v = pts and PTS_MASK
        buf[off + 0] = (prefix or (((v ushr 29) and 0x0E).toInt()) or 0x01).toByte()
        buf[off + 1] = ((v ushr 22) and 0xFF).toByte()
        buf[off + 2] = ((((v ushr 14) and 0xFE).toInt()) or 0x01).toByte()
        buf[off + 3] = ((v ushr 7) and 0xFF).toByte()
        buf[off + 4] = ((((v shl 1) and 0xFE).toInt()) or 0x01).toByte()
    }

    private fun decodePcrBase(buf: ByteArray, off: Int): Long {
        val b0 = (buf[off + 0].toLong() and 0xFF) shl 25
        val b1 = (buf[off + 1].toLong() and 0xFF) shl 17
        val b2 = (buf[off + 2].toLong() and 0xFF) shl 9
        val b3 = (buf[off + 3].toLong() and 0xFF) shl 1
        val b4 = (buf[off + 4].toLong() and 0x80) ushr 7
        return (b0 or b1 or b2 or b3 or b4) and PTS_MASK
    }

    private fun encodePcrBase(buf: ByteArray, off: Int, pcrBase: Long) {
        val v = pcrBase and PTS_MASK
        buf[off + 0] = ((v ushr 25) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 17) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 9) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 1) and 0xFF).toByte()
        val lowKeep = buf[off + 4].toInt() and 0x7F
        buf[off + 4] = ((((v and 0x01) shl 7).toInt()) or lowKeep).toByte()
    }

    /**
     * Filtro de fluxo MPEG-TS com estado por conexão da TV (100% KMP — Android, Desktop e iOS):
     * - Suaviza o Continuity Counter de 4 bits por PID;
     * - Mantém PTS/DTS/PCR monotônicos através de `#EXT-X-DISCONTINUITY` e reconexões!
     */
    class MpegTsNormalizer {
        private val ccByPid = HashMap<Int, Int>()
        private val leftover = ByteArray(PACKET_SIZE * 2)
        private var leftoverLen = 0
        private var ptsOffset: Long = 0L
        private var lastAdjustedPts: Long = -1L
        private var discontinuityPending = false

        fun markDiscontinuity() {
            discontinuityPending = true
        }

        inline fun normalizeChunk(chunk: ByteArray, length: Int, emit: (ByteArray) -> Unit) {
            val normalized = normalizeBytes(chunk, length)
            if (normalized.isNotEmpty()) {
                emit(normalized)
            }
        }

        fun normalizeBytes(chunk: ByteArray, length: Int): ByteArray {
            if (length <= 0) return ByteArray(0)
            val work = ByteArray(leftoverLen + length)
            if (leftoverLen > 0) {
                leftover.copyInto(work, destinationOffset = 0, startIndex = 0, endIndex = leftoverLen)
            }
            chunk.copyInto(work, destinationOffset = leftoverLen, startIndex = 0, endIndex = length)
            leftoverLen = 0

            var pos = 0
            val outBuf = GrowableByteBuffer(work.size)
            while (pos + PACKET_SIZE <= work.size) {
                if (work[pos] != SYNC_BYTE) {
                    pos += 1
                    continue
                }
                normalizePacketInPlace(work, pos)
                outBuf.write(work, pos, PACKET_SIZE)
                pos += PACKET_SIZE
            }

            val rem = work.size - pos
            if (rem in 1..leftover.size) {
                work.copyInto(leftover, destinationOffset = 0, startIndex = pos, endIndex = pos + rem)
                leftoverLen = rem
            }

            return if (outBuf.size > 0) outBuf.toByteArray() else ByteArray(0)
        }

        private fun normalizePacketInPlace(buf: ByteArray, base: Int) {
            val pid = ((buf[base + 1].toInt() and 0x1F) shl 8) or (buf[base + 2].toInt() and 0xFF)
            if (pid == 0x1FFF) return

            val afc = (buf[base + 3].toInt() ushr 4) and 0x03
            val hasAdaptation = (afc and 0x02) != 0
            val hasPayload = (afc and 0x01) != 0

            if (hasPayload) {
                val nextCc = ((ccByPid[pid] ?: -1) + 1) and 0x0F
                ccByPid[pid] = nextCc
                buf[base + 3] = ((buf[base + 3].toInt() and 0xF0) or nextCc).toByte()
            }

            if (hasAdaptation) {
                val adaptLen = buf[base + 4].toInt() and 0xFF
                if (adaptLen >= 7 && (buf[base + 5].toInt() and 0x10) != 0) {
                    val rawPcr = decodePcrBase(buf, base + 6)
                    val adjPcr = adjustTimestamp(rawPcr)
                    encodePcrBase(buf, base + 6, adjPcr)
                }
            }

            val pusi = (buf[base + 1].toInt() and 0x40) != 0
            if (pusi && hasPayload) {
                val p = payloadOffset(buf, base)
                if (p >= 0 && p + 14 <= base + PACKET_SIZE &&
                    buf[p] == 0.toByte() && buf[p + 1] == 0.toByte() && buf[p + 2] == 1.toByte()
                ) {
                    val streamId = buf[p + 3].toInt() and 0xFF
                    if (streamId in 0xC0..0xEF) {
                        val ptsDtsFlags = (buf[p + 7].toInt() ushr 6) and 0x03
                        if (ptsDtsFlags == 2) {
                            val rawPts = decodePts(buf, p + 9)
                            val adjPts = adjustTimestamp(rawPts)
                            encodePts(buf, p + 9, adjPts, prefix = 0x20)
                        } else if (ptsDtsFlags == 3 && p + 19 <= base + PACKET_SIZE) {
                            val rawPts = decodePts(buf, p + 9)
                            val rawDts = decodePts(buf, p + 14)
                            val adjPts = adjustTimestamp(rawPts)
                            val adjDts = (rawDts + ptsOffset) and PTS_MASK
                            encodePts(buf, p + 9, adjPts, prefix = 0x30)
                            encodePts(buf, p + 14, adjDts, prefix = 0x10)
                        }
                    }
                }
            }
        }

        private fun adjustTimestamp(raw90k: Long): Long {
            if (lastAdjustedPts < 0L) {
                lastAdjustedPts = raw90k
                ptsOffset = 0L
                discontinuityPending = false
                return raw90k
            }
            var candidate = (raw90k + ptsOffset) and PTS_MASK
            var diff = (candidate - lastAdjustedPts) and PTS_MASK
            if (diff >= (PTS_MODULO ushr 1)) diff -= PTS_MODULO

            val jumpBack = diff < -270_000L // -3s
            val jumpForward = diff > 1_800_000L // +20s
            if (discontinuityPending || jumpBack || jumpForward) {
                val target = (lastAdjustedPts + 3600L) and PTS_MASK
                ptsOffset = (target - raw90k) and PTS_MASK
                candidate = target
                discontinuityPending = false
            }

            val fwd = (candidate - lastAdjustedPts) and PTS_MASK
            if (fwd < (PTS_MODULO ushr 1)) {
                lastAdjustedPts = candidate
            }
            return candidate
        }
    }
}
