package eu.kanade.tachiyomi.ui.stream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.LocalNavigationBarPadding
import tachiyomi.presentation.core.i18n.stringResource

data object YouTubeTab : Tab {
    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 7u,
            title = stringResource(AYMR.strings.label_youtube),
            icon = rememberVectorPainter(Icons.Outlined.SmartDisplay),
        )

    @Composable
    override fun Content() = StreamContent(StreamSite.YouTube)
}

data object YtMusicTab : Tab {
    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 8u,
            title = stringResource(AYMR.strings.label_yt_music),
            icon = rememberVectorPainter(Icons.Outlined.LibraryMusic),
        )

    @Composable
    override fun Content() = StreamContent(StreamSite.YtMusic)
}

@Composable
private fun StreamContent(site: StreamSite) {
    val context = LocalContext.current
    val state = remember(site) { StreamWebState.get(site, context) }

    // Back: exit fullscreen / go back in the page; otherwise let the tab navigator handle it.
    BackHandler(enabled = state.canGoBack || state.inFullscreen) { state.goBack() }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(LocalNavigationBarPadding.current),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                state.webView.also {
                    (it.parent as? android.view.ViewGroup)?.removeView(it)
                    state.attach(ctx)
                }
            },
            onRelease = { state.detach() },
        )
    }
}

