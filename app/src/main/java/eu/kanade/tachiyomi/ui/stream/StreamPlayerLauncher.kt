package eu.kanade.tachiyomi.ui.stream

import android.content.Context
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.interactor.GetEpisodeByUrlAndAnimeId
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Resolves a YouTube watch URL into direct streams (NewPipeExtractor) and plays it in the app's own
 * mpv [PlayerActivity], so frame generation, upscaling, shaders, PiP, etc. all apply.
 *
 * The player is bound to the anime/episode database, so videos are stored as episodes of a single
 * hidden "YouTube" entry (not a favorite). Direct stream URLs are handed over as pre-resolved hosters.
 */
object StreamPlayerLauncher {

    private const val YT_SOURCE_ID = -7001L
    private const val YT_ANIME_URL = "kuma://youtube"

    private var initialized = false

    @Synchronized
    private fun ensureInit() {
        if (initialized) return
        NewPipe.init(NewPipeDownloader(Injekt.get<NetworkHelper>().client), Localization.DEFAULT)
        initialized = true
    }

    /** Normalizes `/watch?v=ID`, `youtu.be/ID`, `/shorts/ID` links; null if not a video link. */
    fun normalize(raw: String): String? {
        val uri = android.net.Uri.parse(raw)
        val host = uri.host ?: return null
        val id = when {
            host == "youtu.be" -> uri.lastPathSegment
            host.endsWith("youtube.com") && uri.path == "/watch" -> uri.getQueryParameter("v")
            host.endsWith("youtube.com") && uri.path?.startsWith("/shorts/") == true -> uri.lastPathSegment
            else -> null
        }
        return id?.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }
    }

    suspend fun play(context: Context, rawUrl: String) {
        val url = normalize(rawUrl) ?: error("Not a YouTube video link")
        val (info, videos) = withContext(Dispatchers.IO) {
            ensureInit()
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            info to buildVideos(info)
        }
        if (videos.isEmpty()) error("No playable stream found")

        val (animeId, episodeId) = withContext(Dispatchers.IO) { storeEpisode(url, info.name) }
        val intent = PlayerActivity.newIntent(context, animeId, episodeId, videos.toHosterList(), 0, 0)
        if (context !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun buildVideos(info: StreamInfo): List<Video> {
        val headers = Headers.headersOf("User-Agent", StreamScripts.USER_AGENT)
        val progressive = DeliveryMethod.PROGRESSIVE_HTTP

        val audio = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == progressive }
            .maxByOrNull { it.averageBitrate }
        val audioTracks = audio?.let { listOf(Track(it.content, "Audio")) } ?: emptyList()

        val result = mutableListOf<Video>()

        // Highest quality: video-only stream + separate audio track (mpv merges them).
        if (audio != null) {
            info.videoOnlyStreams
                .filter { it.isUrl && it.deliveryMethod == progressive }
                .sortedWith(compareByDescending({ it.height }, { it.bitrate }))
                .distinctBy { it.height }
                .forEach {
                    result += Video(
                        videoUrl = it.content,
                        videoTitle = "${it.height}p ${it.format?.name.orEmpty()}".trim(),
                        resolution = it.height,
                        bitrate = it.bitrate,
                        headers = headers,
                        audioTracks = audioTracks,
                    )
                }
        }
        // Muxed fallbacks (usually <= 720p).
        info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == progressive }
            .sortedByDescending { it.height }
            .forEach {
                result += Video(
                    videoUrl = it.content,
                    videoTitle = "${it.height}p (muxed)",
                    resolution = it.height,
                    bitrate = it.bitrate,
                    headers = headers,
                )
            }
        // Live streams.
        if (result.isEmpty() && info.hlsUrl.isNotBlank()) {
            result += Video(videoUrl = info.hlsUrl, videoTitle = "HLS", headers = headers)
        }
        // Audio-only (e.g. music-only content).
        if (result.isEmpty() && audio != null) {
            result += Video(videoUrl = audio.content, videoTitle = "Audio", headers = headers)
        }
        return result.mapIndexed { i, v -> if (i == 0) v.copy(preferred = true) else v }
    }

    private suspend fun storeEpisode(videoUrl: String, title: String): Pair<Long, Long> {
        val networkToLocalAnime: NetworkToLocalAnime = Injekt.get()
        val getEpisode: GetEpisodeByUrlAndAnimeId = Injekt.get()
        val episodeRepository: EpisodeRepository = Injekt.get()

        val anime = networkToLocalAnime.await(
            Anime.create().copy(
                url = YT_ANIME_URL,
                source = YT_SOURCE_ID,
                title = "YouTube",
                initialized = true,
            ),
        )
        val now = System.currentTimeMillis()
        val existing = getEpisode.await(videoUrl, anime.id)
        val episode = existing ?: episodeRepository.addAllEpisodes(
            listOf(
                Episode.create().copy(
                    animeId = anime.id,
                    url = videoUrl,
                    name = title,
                    dateUpload = now,
                    dateFetch = now,
                    sourceOrder = now / 1000,
                    episodeNumber = 1.0,
                ),
            ),
        ).first()
        return anime.id to episode.id
    }
}
