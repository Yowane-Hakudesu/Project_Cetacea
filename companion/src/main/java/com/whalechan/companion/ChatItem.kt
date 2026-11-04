package com.whalechan.companion

/** 聊天列表里的一行。 */
sealed interface ChatItem {
    val ts: Long

    data class Text(
        val id: String,
        val text: String,
        val mine: Boolean,
        override val ts: Long
    ) : ChatItem

    data class Image(
        val id: String,
        val path: String?,
        val name: String,
        val mine: Boolean,
        override val ts: Long
    ) : ChatItem

    data class File(
        val id: String,
        val path: String?,
        val name: String,
        val size: Long,
        val mine: Boolean,
        override val ts: Long
    ) : ChatItem

    data class System(val text: String, override val ts: Long) : ChatItem

    data class Time(override val ts: Long) : ChatItem
}
