package com.iamd2epak.ytplayer

data class Lecture(val title: String, val video: String)

/**
 * Your course lectures. `video` can be a bare 11-char YouTube ID or any YouTube link
 * (youtu.be/..., watch?v=..., /embed/..., /shorts/...). Unlisted videos work.
 * The link is never shown to the user; only the ID is passed into the private player.
 */
object Lectures {
    val all = listOf(
        Lecture("Lecture 1 - Introduction", "https://youtu.be/JEla_mdlfxs"),
    )
}

object YouTubeId {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val PATTERNS = listOf(
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
        Regex("[?&]v=([A-Za-z0-9_-]{11})"),
        Regex("/(?:embed|shorts|live|v)/([A-Za-z0-9_-]{11})"),
    )

    /** Returns a validated 11-char video ID, or null. */
    fun parse(input: String): String? {
        val s = input.trim()
        if (ID.matches(s)) return s
        return PATTERNS.firstNotNullOfOrNull { it.find(s)?.groupValues?.get(1) }
    }
}
