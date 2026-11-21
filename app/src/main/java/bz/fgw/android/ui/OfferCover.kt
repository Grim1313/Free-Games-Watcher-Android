package bz.fgw.android.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import bz.fgw.android.providers.StoreUrls
import coil3.compose.AsyncImage

@Composable
fun OfferCover(store: String, url: String?, modifier: Modifier = Modifier) {
    if (url == null || !StoreUrls.isCover(store, url)) return
    var failed by remember(url) { mutableStateOf(false) }
    if (!failed) AsyncImage(
        model = url, contentDescription = null, modifier = modifier.aspectRatio(16f / 9f),
        contentScale = ContentScale.Crop,
        onError = { failed = true },
    )
}
