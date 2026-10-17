package com.theveloper.pixelplay.data.s3

import com.theveloper.pixelplay.data.database.SongMatchKey
import com.theveloper.pixelplay.data.stream.CloudMusicUtils
import kotlin.math.abs

/**
 * On-device tracks keyed by title + primary artist. The S3 mirror skips objects that have a
 * local copy, so the library shows the local file instead of a duplicate stream.
 */
class LocalTrackIndex(localSongs: List<SongMatchKey>) {

    private val durations: Map<String, List<Long>> =
        localSongs.groupBy({ matchKey(it.title, it.artistName) }, { it.duration })

    fun hasLocalCopy(title: String, artist: String, durationMs: Long): Boolean {
        val key = matchKey(title, artist)
        if (key.startsWith("|")) return false // no usable title
        val local = durations[key] ?: return false
        // Unknown durations (S3 tags not probed yet) match on title + artist alone.
        return durationMs <= 0 || local.any { it <= 0 || abs(it - durationMs) <= DURATION_TOLERANCE_MS }
    }

    companion object {
        const val DURATION_TOLERANCE_MS = 3_000L
        private val ARTIST_SEPARATORS = Regex("[,;/&]")
        private val BRACKETED_SUFFIX = Regex("""\s*[(\[][^)\]]*[)\]]""")

        /** "title|primary artist", lowercased, without "(Remastered)"-style suffixes or punctuation. */
        fun matchKey(title: String, artist: String): String {
            fun norm(value: String) = value.lowercase()
                .replace(BRACKETED_SUFFIX, "")
                .filter { it.isLetterOrDigit() }
            // Tags differ in how they join artists ("A, B" vs "A; B"), so also cut at common separators.
            val primaryArtist = CloudMusicUtils.parseArtistNames(artist).firstOrNull().orEmpty()
                .split(ARTIST_SEPARATORS).first()
            return "${norm(title)}|${norm(primaryArtist)}"
        }
    }
}
