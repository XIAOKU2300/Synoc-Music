package com.lladlam.melox.core.synclink

import java.io.ByteArrayOutputStream

/**
 * Minimal proto3 codec for the SyncLink wire format. The schema was recovered from the
 * official client's embedded descriptor (see synclink/SyncLinkMsg.proto); only the messages
 * MeloX actually exchanges are modelled here.
 */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun int(field: Int, value: Int): ProtoWriter {
        if (value != 0) { tag(field, 0); varint(value.toLong()) }
        return this
    }

    fun long(field: Int, value: Long): ProtoWriter {
        if (value != 0L) { tag(field, 0); varint(value) }
        return this
    }

    fun string(field: Int, value: String?): ProtoWriter {
        if (!value.isNullOrEmpty()) bytes(field, value.toByteArray(Charsets.UTF_8))
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2); varint(value.size.toLong()); out.write(value)
        return this
    }

    fun message(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.build())

    fun build(): ByteArray = out.toByteArray()
}

/** Decoded message: field number -> raw values (Long for varint/fixed64, Int for fixed32, ByteArray for LEN). */
class ProtoMsg private constructor(private val fields: Map<Int, List<Any>>) {
    fun long(field: Int): Long = when (val v = fields[field]?.lastOrNull()) {
        is Long -> v
        is Int -> v.toLong()
        else -> 0L
    }

    fun int(field: Int): Int = long(field).toInt()

    fun float(field: Int): Float = when (val v = fields[field]?.lastOrNull()) {
        is Int -> java.lang.Float.intBitsToFloat(v)
        else -> 0f
    }

    fun bool(field: Int): Boolean = long(field) != 0L

    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray

    fun string(field: Int): String = bytes(field)?.toString(Charsets.UTF_8).orEmpty()

    fun msg(field: Int): ProtoMsg? = bytes(field)?.let { parse(it) }

    fun msgs(field: Int): List<ProtoMsg> =
        fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.let(::parse) }

    companion object {
        val EMPTY = ProtoMsg(emptyMap())

        fun parse(data: ByteArray?): ProtoMsg {
            if (data == null || data.isEmpty()) return EMPTY
            val map = HashMap<Int, MutableList<Any>>()
            var pos = 0
            fun readVarint(): Long {
                var shift = 0
                var result = 0L
                while (pos < data.size) {
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) break
                }
                throw IllegalArgumentException("bad varint")
            }
            try {
                while (pos < data.size) {
                    val key = readVarint()
                    val field = (key ushr 3).toInt()
                    val value: Any = when ((key and 7).toInt()) {
                        0 -> readVarint()
                        1 -> {
                            require(pos + 8 <= data.size)
                            var v = 0L
                            for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                            pos += 8
                            v
                        }
                        2 -> {
                            val len = readVarint().toInt()
                            require(len >= 0 && pos + len <= data.size)
                            data.copyOfRange(pos, pos + len).also { pos += len }
                        }
                        5 -> {
                            require(pos + 4 <= data.size)
                            var v = 0
                            for (i in 0 until 4) v = v or ((data[pos + i].toInt() and 0xFF) shl (8 * i))
                            pos += 4
                            v
                        }
                        else -> throw IllegalArgumentException("unsupported wire type")
                    }
                    map.getOrPut(field) { ArrayList(1) }.add(value)
                }
            } catch (_: IllegalArgumentException) {
                // Keep whatever decoded cleanly; a truncated tail must not kill the session.
            }
            return ProtoMsg(map)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------------------------

data class SlPlayInfo(
    val songId: Long = 0,
    val filename: String = "",
    val filepath: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val composer: String = "",
    val year: Int = 0,
    /** CUE start offset in seconds (-1 / 0 for plain files). */
    val time: Int = 0,
    val timeEnd: Int = 0,
    val bitsPerSample: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    /** Seconds. */
    val currentTime: Int = 0,
    /** Seconds. */
    val totalTime: Int = 0,
    val filesize: Int = 0,
    val playType: Int = 0,
    val favor: Int = 0,
    val initial: Int = 0,
    val coverUrl: String = "",
    val lrcUrl: String = "",
) {
    val displayTitle: String
        get() = title.ifBlank { filename.substringBeforeLast('.').ifBlank { filename } }
    /**
     * Container/codec label like the official ViewUtil: the extension of the *file path* (the title
     * field may be a CUE track name without one). DSF/DFF are DSD.
     */
    val codec: String
        get() {
            val ext = filepath.ifBlank { filename }.substringAfterLast('/').substringAfterLast('.', "").uppercase()
            return when {
                ext == "DSF" || ext == "DFF" -> "DSD"
                ext.length in 2..5 && ext.all { it.isLetterOrDigit() } -> ext
                else -> ""
            }
        }
    val isDsd: Boolean get() = codec == "DSD"
    val isEmpty: Boolean get() = filepath.isBlank() && filename.isBlank() && title.isBlank()

    companion object {
        fun from(m: ProtoMsg) = SlPlayInfo(
            songId = m.long(1), filename = m.string(2), filepath = m.string(3), title = m.string(4),
            artist = m.string(5), album = m.string(6), genre = m.string(7), composer = m.string(8),
            year = m.int(9), time = m.int(10), timeEnd = m.int(11), bitsPerSample = m.int(12),
            sampleRate = m.int(13), bitrate = m.int(14), currentTime = m.int(15), totalTime = m.int(16),
            filesize = m.int(17), playType = m.int(18), favor = m.int(19), initial = m.int(20),
            coverUrl = m.string(21), lrcUrl = m.string(22),
        )
    }
}

data class SlSong(
    val songId: Long,
    val filename: String,
    val filepath: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val composer: String,
    val year: Int,
    val time: Int,
    val timeEnd: Int,
    val coverUrl: String,
    val lrcUrl: String,
) {
    val displayTitle: String
        get() = title.ifBlank { filename.substringBeforeLast('.').ifBlank { filepath.substringAfterLast('/') } }

    /** Seconds; only CUE tracks carry a length in SongDetail. */
    val durationSec: Int get() = if (time >= 0 && timeEnd > time) timeEnd - time else 0

    val parentPath: String get() = filepath.substringBeforeLast('/', "")

    companion object {
        fun from(m: ProtoMsg) = SlSong(
            songId = m.long(1), filename = m.string(2), filepath = m.string(3), title = m.string(4),
            artist = m.string(5), album = m.string(6), genre = m.string(7), composer = m.string(8),
            year = m.int(9), time = m.int(10), timeEnd = m.int(11), coverUrl = m.string(13), lrcUrl = m.string(14),
        )
    }
}

data class SlAlbum(val album: String, val artist: String, val id: Int, val coverUrl: String) {
    companion object {
        fun from(m: ProtoMsg) = SlAlbum(m.string(1), m.string(2), m.int(3), m.string(4))
    }
}

data class SlArtist(val artist: String, val id: Int, val count: Int, val coverUrl: String) {
    companion object {
        fun from(m: ProtoMsg) = SlArtist(m.string(1), m.int(2), m.int(3), m.string(4))
    }
}

data class SlGenre(val genre: String, val albumTotal: Int, val id: Int) {
    companion object {
        fun from(m: ProtoMsg) = SlGenre(m.string(1), m.int(2), m.int(3))
    }
}

data class SlPlaylist(val id: Int, val name: String) {
    companion object {
        fun from(m: ProtoMsg) = SlPlaylist(m.int(1), m.string(2))
    }
}

/** PathItem.type: 1 = storage root/dir, 4 = folder, 8 = audio file (per official FolderActivity). */
data class SlPathItem(
    val type: Int,
    val name: String,
    val path: String,
    val time: Int,
    val timeEnd: Int,
    val parentPath: String,
    val id: Int,
) {
    val isFile: Boolean get() = type == 8
    companion object {
        fun from(m: ProtoMsg) = SlPathItem(m.int(1), m.string(2), m.string(3), m.int(4), m.int(5), m.string(6), m.int(7))
    }
}

data class SlVolume(val max: Int = 0, val cur: Int = 0, val mute: Int = 0, val lo: Int = 0) {
    companion object {
        fun from(m: ProtoMsg?) = m?.let { SlVolume(it.int(1), it.int(2), it.int(3), it.int(4)) } ?: SlVolume()
    }
}

data class SlDeviceInfo(
    val version: String = "",
    val ip: String = "",
    val mac: String = "",
    val btMac: String = "",
    val serial: String = "",
)

data class SlStorage(val name: String, val path: String, val usage: Float, val total: Float)

data class SlBattery(val level: Int, val status: Int)

data class SlPage<T>(val total: Int, val items: List<T>)

/** Wire command numbers (SynclinkConstants). Destructive ones are intentionally absent. */
object SlCmd {
    const val LOGIN_REQ = 257
    const val LOGIN_RESP = 258
    const val LOGOUT_REQ = 259
    /** Server-initiated logout; the official client drops the session on receipt. */
    const val LOGOUT_NOTIFY = 260

    const val GET_ALL_SONG_REQ = 513
    const val GET_ALL_SONG_RESP = 514
    const val BROWSE_PATH_REQ = 515
    const val BROWSE_PATH_RESP = 516
    const val GET_ALBUM_LIST_REQ = 517
    const val GET_ALBUM_LIST_RESP = 518
    const val GET_ARTIST_LIST_REQ = 519
    const val GET_ARTIST_LIST_RESP = 520
    const val START_UPDATE_SONGS_REQ = 523
    const val UPDATING_NOTIFY = 527
    const val FINISH_UPDATE_NOTIFY = 529
    const val GET_GENRE_LIST_REQ = 530
    const val GET_GENRE_LIST_RESP = 531
    const val GET_ALBUM_BY_ARTIST_REQ = 532
    const val GET_ALBUM_BY_GENRE_REQ = 534
    const val GET_SONG_LIST_BY_ALBUM_REQ = 545
    const val GET_SONG_LIST_BY_ALBUM_RESP = 546
    const val GET_SONG_LIST_BY_ARTIST_REQ = 547
    const val GET_SONG_LIST_BY_ARTIST_RESP = 548
    const val GET_SONG_LIST_BY_GENRE_ALBUM_REQ = 555
    const val GET_SONG_LIST_BY_GENRE_ALBUM_RESP = 556
    const val GET_SONG_LIST_BY_PLAYLIST_REQ = 557
    const val GET_SONG_LIST_BY_PLAYLIST_RESP = 559
    const val GET_ALBUM_BY_ARTIST_RESP = 560
    const val GET_ALBUM_BY_GENRE_RESP = 562

    const val GET_PLAY_MODE_REQ = 769
    const val GET_PLAY_MODE_RESP = 770
    const val SET_PLAY_MODE_REQ = 771
    const val SET_PLAY_MODE_RESP = 772
    const val SET_PLAY_MODE_NOTIFY = 773
    const val GET_VOLUME_INFO_REQ = 774
    const val GET_VOLUME_INFO_RESP = 775
    const val SET_VOLUME_REQ = 776
    const val SET_VOLUME_RESP = 777
    const val VOLUME_INFO_NOTIFY = 778
    const val GET_PLAY_INFO_REQ = 779
    const val GET_PLAY_INFO_RESP = 780
    const val GET_PLAY_INFO_NOTIFY = 781
    const val CUR_PLAY_TIME_NOTIFY = 782
    const val ENABLE_PLAYTIME_NOTIFY_REQ = 785
    const val PLAY_CONTROL_REQ = 787
    const val PLAY_CONTROL_RESP = 788
    const val PLAY_CONTROL_NOTIFY = 789
    const val PLAY_SEEK_REQ = 790
    const val PLAY_URL_REQ = 792
    const val MESSAGE_NOTIFY = 800
    const val GET_PLAY_QUEUE_REQ = 801
    const val GET_PLAY_QUEUE_RESP = 802
    const val GET_PLAY_STATUS_REQ = 803
    const val GET_PLAY_STATUS_RESP = 804
    const val GET_PLAY_STATUS_NOTIFY = 805

    const val GET_PLAYLIST_LIST_REQ = 1027
    const val GET_PLAYLIST_LIST_RESP = 1028
    const val GET_MEMORY_INFO_REQ = 1285
    const val GET_MEMORY_INFO_RESP = 1286
    const val GET_MEMORY_INFO_NOTIFY = 1287
    const val GET_ALL_HR_REQ = 1288
    const val GET_ALL_HR_RESP = 1289
    const val GET_ALL_FAV_REQ = 1290
    const val GET_ALL_FAV_RESP = 1291
    const val GET_RECENTPLAY_REQ = 1292
    const val GET_RECENTPLAY_RESP = 1293
    const val SET_FAVOR_REQ = 1298
    const val FAVOR_NOTIFY = 1299
    const val ADD_FAVOR_RESP = 1301
    const val REFRESH_NOTIFY = 1305
    const val PROMPT_NOTIFY = 1306
    const val SORT_TYPE_NOTIFY = 1310
    const val CLEAR_DATA_NOTIFY = 1311
    const val SYNCLINK_PAUSED = 1313

    const val GET_DEVICEINFO_REQ = 1569
    const val GET_DEVICEINFO_RESP = 1570
    const val GET_COVER_REQ = 1637
    const val GET_COVER_RESP = 1638
    const val GET_LRC_REQ = 1639
    const val GET_LRC_RESP = 1640
    const val GET_BATTERY_REQ = 1641
    const val GET_BATTERY_RESP = 1642
    const val GET_SEARCH_REQ = 1664
    const val GET_SEARCH_RESP = 1665
    const val HEART_BEAT_REQ = 1792
    const val HEART_BEAT_RESP = 1793
}

object SlControl {
    const val PLAY = 0
    const val NEXT = 1
    const val PREV = 2
    const val STOP = 3
    const val MUTE = 4
    const val PAUSE = 5
}

object SlMode {
    const val NORMAL = 0
    const val REPEAT_ONE = 1
    const val RANDOM = 2
    const val REPEAT_ALL = 3
}

/** PlayUrlReq.playType values. */
object SlPlayType {
    const val ALLMUSIC = 2
    const val HR = 3
    const val FAV = 4
    const val RECENTPLAY = 5
    const val ALBUM = 8
    const val SINGER = 9
    const val GENRE_ALBUM = 14
    const val PLAYLIST = 15
    const val FOLDER = 16
    const val QUEUE = 17
    const val ALLMUSIC_SEARCH = 18
}

/**
 * Device settings sharing the "single int32 field = 1" shape. [get] is an empty request,
 * [set] carries the value, [getResp]/[setResp]/[notify] all decode the same way.
 */
enum class SlSetting(
    val label: String,
    val get: Int,
    val getResp: Int,
    val set: Int,
    val setResp: Int,
    val notify: Int,
) {
    Gain("增益", 1538, 1539, 1536, 1537, 1540),
    Filter("数字滤波器", 1543, 1544, 1541, 1542, 1545),
    Balance("左右声道平衡", 1548, 1549, 1546, 1547, 1550),
    InputSource("输入源", 1553, 1554, 1551, 1552, 1555),
    DsdMode("DSD 输出", 1558, 1559, 1556, 1557, 1560),
    UsbAudio("USB 音频", 1563, 1564, 1561, 1562, 1565),
    BtQuality("蓝牙音质", 1573, 1574, 1571, 1572, 1575),
    UacMode("UAC 模式", 1596, 1597, 1594, 1595, 1598),
    Backlight("屏幕亮度", 1601, 1602, 1599, 1600, 1603),
    ScreenRotate("屏幕旋转", 1606, 1607, 1604, 1605, 1608),
    AutoShutdown("自动关机", 1611, 1612, 1609, 1610, 1613),
    BacklightTime("息屏时间", 1619, 1620, 1617, 1618, 1621),
    LockKey("按键锁", 1624, 1625, 1622, 1623, 1626),
    OutputWay("输出方式", 1629, 1630, 1627, 1628, 1631),
    SrcMode("采样率转换", 1634, 1635, 1632, 1633, 1636),
    HeadphoneMode("耳机模式", 1645, 1646, 1643, 1644, 1647),
    ToneChoice("音色", 1650, 1651, 1648, 1649, 1652),
    MqaOutput("MQA 输出", 1660, 1661, 1658, 1659, 1662);

    companion object {
        private val byResp: Map<Int, SlSetting> = buildMap {
            values().forEach { s -> put(s.getResp, s); put(s.setResp, s); put(s.notify, s) }
        }

        fun forResponse(cmd: Int): SlSetting? = byResp[cmd]
    }
}

/** Device types that run the Linux firmware: bitrate in bps and seek offsets are CUE-relative. */
fun slIsAndroidDevice(deviceType: Int): Boolean = deviceType == 0 || deviceType == 9

fun slDeviceModelName(deviceType: Int): String = when (deviceType) {
    0 -> "Android App"
    1 -> "M0"
    2 -> "M2X"
    3 -> "M5s"
    4 -> "Q1"
    5 -> "M6"
    6 -> "M6 Pro"
    7 -> "H7"
    8 -> "CD (Linux)"
    9 -> "CD (Android)"
    10 -> "XP10"
    11 -> "ET3"
    12 -> "H5"
    13 -> "CD80"
    14 -> "CA80"
    15 -> "EH3"
    16 -> "EC Mini"
    17 -> "XP1"
    18 -> "H1U"
    19 -> "SCD1.3"
    20 -> "CD1.3"
    21 -> "Linux 便携"
    22 -> "CDS100 IV"
    23 -> "CD80 II"
    24 -> "CT90"
    25 -> "SCD3.3"
    26 -> "OC93 II"
    27 -> "SCD1.3R"
    else -> "未知设备 ($deviceType)"
}
