package com.wanderwildwood.amime.mesh

import java.io.File

/**
 * The conversations, written down.
 *
 * A mesh message exists in exactly one place once it has been handed over: the radio holds
 * what arrived while nothing was listening, gives it to the app that asks, and then does not
 * have it any more. So an app that kept its messages in memory would be the only copy of them
 * and would lose every one of them the next time Android reclaimed the process — which on a
 * phone is not an unusual event but the ordinary one.
 *
 * Plain lines rather than a database, because the whole of it is a few hundred short strings
 * and a table would be a dependency, a schema and a migration for something a person could
 * read with `cat`. The text is last on the line so that no field after it has to be found by
 * counting past whatever somebody typed.
 */
class MessageLog(private val file: File) {

    /** What was on disk, and where the numbering had got to. */
    data class Restored(
        val conversations: Map<List<Byte>, List<Message>> = emptyMap(),
        val nextId: Long = 1L,
        val readUpTo: Map<List<Byte>, Long> = emptyMap(),
        /** Read from the earlier format, so worth writing again at once in this one. */
        val former: Boolean = false,
    )

    /**
     * Read the log, treating anything unreadable as absent rather than as a reason to fail.
     *
     * A file that is there but cannot be read (another format, a later version's) is moved
     * aside first, to [unreadable] or, where an earlier one already sits there, to a name of
     * its own beside it, so the next write does not put an empty log over it.
     *
     * A file that could not be opened just now is another matter: that says nothing about
     * what is in it. It is left where it is, and [write] refuses until a read succeeds, so
     * the log is never replaced by one that never saw it.
     */
    @Synchronized
    fun read(): Restored {
        if (!file.exists()) {
            mayWrite = true
            return Restored()
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            mayWrite = false
            return Restored()
        }
        if (text.isBlank() || text.lineSequence().first().let { it == HEADER || it == FORMER_HEADER }) {
            runCatching { decode(text) }.getOrNull()?.let {
                mayWrite = true
                return it
            }
        }
        // Only once it is safely aside may a new log take its name.
        mayWrite = file.renameTo(aside())
        return Restored()
    }

    /** Where a log this version cannot read is kept, untouched. */
    val unreadable: File get() = File(file.parentFile, file.name + ".unreadable")

    /** False while the log on disk is one that was not read, and so must not be written over. */
    @Volatile private var mayWrite = true

    /** [unreadable], or the first free numbered name beside it: an earlier one is never replaced. */
    private fun aside(): File {
        if (!unreadable.exists()) return unreadable
        var n = 2
        while (true) {
            val next = File(file.parentFile, "${unreadable.name}-$n")
            if (!next.exists()) return next
            n++
        }
    }

    /**
     * Write the whole log.
     *
     * Through a temporary file and a rename, which is atomic: a phone that dies mid-write
     * otherwise leaves a file that is half of two versions, and the half it keeps is the one
     * that fails to parse.
     */
    @Synchronized
    fun write(
        conversations: Map<List<Byte>, List<Message>>,
        readUpTo: Map<List<Byte>, Long> = emptyMap(),
    ) {
        if (!mayWrite) throw java.io.IOException("${file.name} was not read, so it is not written over")
        // Synced before the rename, so a power cut never leaves the new name on an empty file.
        val temporary = File(file.parentFile, file.name + ".writing")
        java.io.FileOutputStream(temporary).use { out ->
            out.write(encode(conversations, readUpTo).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw java.io.IOException("could not replace ${file.name}")
        }
    }

    companion object {
        /**
         * The first line, so a later format can tell what it is reading.
         *
         * A file that does not start with this is not read at all. Guessing at an unknown
         * format is how a message from some future version arrives in this one as a row of
         * replacement characters that the reader then cannot delete.
         */
        private const val HEADER = "amime-messages 2"

        /**
         * The format before channel threads were filed under a digest of the channel's key.
         *
         * In it a MeshCore channel's thread sat under the channel's secret itself. Read, every
         * channel-sized key is passed through [Channel.threadKey], which is what this version
         * files the same channel under; a person's prefix is shorter and stays as it was.
         */
        private const val FORMER_HEADER = "amime-messages 1"

        /** A channel's thread key is sixteen bytes; a person's prefix is six or four. */
        private const val CHANNEL_KEY_BYTES = 16

        private const val ABSENT = "-"

        /**
         * What marks a line as a read mark rather than a message.
         *
         * Three fields where a message has eight, so a reader that does not know about them
         * skips them for being short rather than mangling them — which is why this did not
         * need a new format number.
         */
        private const val READ_MARK = "read"

        fun encode(
            conversations: Map<List<Byte>, List<Message>>,
            readUpTo: Map<List<Byte>, Long> = emptyMap(),
        ): String = buildString {
            appendLine(HEADER)
            for ((prefix, id) in readUpTo) {
                append(READ_MARK).append('\t')
                append(prefix.joinToString("") { "%02x".format(it) }).append('\t')
                appendLine(id)
            }
            for ((prefix, thread) in conversations) {
                val hex = prefix.joinToString("") { "%02x".format(it) }
                for (message in thread) {
                    append(hex).append('\t')
                    append(message.id).append('\t')
                    append(if (message.mine) "1" else "0").append('\t')
                    append(message.timestamp).append('\t')
                    append(message.delivery.name).append('\t')
                    append(message.snr?.toString() ?: ABSENT).append('\t')
                    append(
                        when (message.direct) {
                            true -> "1"
                            false -> "0"
                            null -> ABSENT
                        },
                    ).append('\t')
                    appendLine(escape(message.text))
                }
            }
        }

        fun decode(text: String): Restored {
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext()) return Restored()
            val former = when (lines.next()) {
                HEADER -> false
                FORMER_HEADER -> true
                else -> return Restored()
            }
            fun filed(key: List<Byte>): List<Byte> =
                if (former && key.size >= CHANNEL_KEY_BYTES) Channel.threadKey(key) else key

            val conversations = linkedMapOf<List<Byte>, MutableList<Message>>()
            val readUpTo = linkedMapOf<List<Byte>, Long>()
            var highestId = 0L
            for (line in lines) {
                if (line.isEmpty()) continue
                val field = line.split('\t', limit = FIELDS)
                if (field.size >= 3 && field[0] == READ_MARK) {
                    val prefix = runCatching { hex(field[1]) }.getOrNull()?.let { filed(it) } ?: continue
                    val id = field[2].toLongOrNull() ?: continue
                    readUpTo[prefix] = id
                    continue
                }
                if (field.size < FIELDS) continue
                val prefix = runCatching { hex(field[0]) }.getOrNull()?.let { filed(it) } ?: continue
                val id = field[1].toLongOrNull() ?: continue
                if (id > highestId) highestId = id
                conversations.getOrPut(prefix) { mutableListOf() }.add(
                    Message(
                        id = id,
                        text = unescape(field[7]),
                        mine = field[2] == "1",
                        timestamp = field[3].toLongOrNull() ?: 0L,
                        delivery = delivery(field[4]),
                        snr = field[5].takeIf { it != ABSENT }?.toFloatOrNull(),
                        direct = when (field[6]) {
                            "1" -> true
                            "0" -> false
                            else -> null
                        },
                    ),
                )
            }
            return Restored(conversations, highestId + 1, readUpTo, former)
        }

        /**
         * What a delivery state means after the app has been closed and opened again.
         *
         * A message that was still in flight is not in flight any more: the acknowledgement
         * it was waiting for travelled while nothing was listening for it, and nothing will
         * ever arrive to settle it now. Reading it back as [Delivery.AWAITING_ACK] would
         * leave a row waiting for ever, which is the one thing the four honest states were
         * built to avoid.
         */
        private fun delivery(name: String): Delivery {
            val stored = Delivery.entries.firstOrNull { it.name == name } ?: Delivery.UNRESOLVED
            return when (stored) {
                Delivery.SENDING, Delivery.AWAITING_ACK -> Delivery.UNRESOLVED
                else -> stored
            }
        }

        private fun hex(text: String): List<Byte> {
            require(text.length % 2 == 0 && text.isNotEmpty()) { "not a key prefix: $text" }
            return (text.indices step 2).map {
                text.substring(it, it + 2).toInt(16).toByte()
            }
        }

        /** Tabs separate the fields and newlines separate the lines, so neither may survive. */
        private fun escape(text: String): String = buildString(text.length) {
            for (character in text) {
                when (character) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(character)
                }
            }
        }

        private fun unescape(text: String): String = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val character = text[index]
                if (character != '\\' || index == text.length - 1) {
                    append(character)
                    index++
                    continue
                }
                when (text[index + 1]) {
                    '\\' -> append('\\')
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(text[index + 1])
                }
                index += 2
            }
        }

        private const val FIELDS = 8
    }
}
