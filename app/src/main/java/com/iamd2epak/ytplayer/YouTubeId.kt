package com.iamd2epak.ytplayer

/** Extracts a YouTube video ID from a bare ID, a link, or shared text containing a link. */
object YouTubeId {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HOSTS = Regex("(?:^|[^A-Za-z0-9.-])(?:(?:www|m|music)\\.)?(?:youtube\\.com|youtube-nocookie\\.com|youtu\\.be)/", RegexOption.IGNORE_CASE)
    private val PATTERNS = listOf(
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})", RegexOption.IGNORE_CASE),
        Regex("[?&]v=([A-Za-z0-9_-]{11})"),
        Regex("/(?:embed|shorts|live|v)/([A-Za-z0-9_-]{11})"),
    )

    /** Returns a validated 11-char video ID, or null. */
    fun parse(input: String): String? {
        val s = input.trim()
        if (ID.matches(s)) return s
        if (!HOSTS.containsMatchIn(s)) return null // only YouTube links
        return PATTERNS.firstNotNullOfOrNull { it.find(s)?.groupValues?.get(1) }
    }

    fun thumbnail(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}
