package com.streams.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Channels the signed-in user joined, shared by every screen. Guests have none. */
object ChannelJoins {
    val joined = MutableStateFlow<Set<String>>(emptySet())

    suspend fun refresh() {
        joined.value = runCatching { Repo.joinedChannelIds() }.getOrDefault(emptySet())
    }

    /** Joins or leaves; updates the screen first and rolls back if the server refuses. */
    suspend fun toggle(channelId: String): Result<Boolean> {
        val wasJoined = channelId in joined.value
        joined.value = if (wasJoined) joined.value - channelId else joined.value + channelId
        return runCatching {
            if (wasJoined) Repo.leaveChannel(channelId) else Repo.joinChannel(channelId)
            !wasJoined
        }.onFailure {
            joined.value = if (wasJoined) joined.value + channelId else joined.value - channelId
        }
    }
}
