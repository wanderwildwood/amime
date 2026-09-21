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
    )

    /** Read the log, treating anything unreadable as absent rather than as a reason to fail. */
    fun read(): Restored =
        runCatching { decode(file.readText()) }.getOrDefault(Restored())

    /**
     * Write the whole log.
     *
     * Through a temporary file and a rename, which is atomic: a phone that dies mid-write
     * otherwise leaves a file that is half of two versions, and the half it keeps is the one
     * that fails to parse.
     */
    fun write(conversations: Map<List<Byte>, List<Message>>) {
        val temporary = File(file.parentFile, file.name + ".writing")
        temporary.writeText(encode(conversations))
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
        private const val HEADER = "amime-messages 1"

        private const val ABSENT = "-"

        fun encode(conversations: Map<List<Byte>, List<Message>>): String = buildString {
            appendLine(HEADER)
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
            if (!lines.hasNext() || lines.next() != HEADER) return Restored()

            val conversations = linkedMapOf<List<Byte>, MutableList<Message>>()
            var highestId = 0L
            for (line in lines) {
                if (line.isEmpty()) continue
                val field = line.split('\t', limit = FIELDS)
                if (field.size < FIELDS) continue
                val prefix = runCatching { hex(field[0]) }.getOrNull() ?: continue
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
            return Restored(conversations, highestId + 1)
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
