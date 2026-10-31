package com.mygallery.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** People tab: scan for faces, see groups, and open a group's photos in the viewer. */
@Composable
fun FacesUi(
    media: List<GalleryMedia>,
    onOpen: (List<GalleryMedia>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelReady = remember { FaceScanner.modelAvailable(context) }
    var index by remember { mutableStateOf(FaceIndex()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(Unit) { index = withContext(Dispatchers.IO) { FaceStore.load(context) } }
    val clusters = remember(index) { peopleClusters(index) }
    val byUri = remember(media) { media.associateBy { it.uri.toString() } }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.s_people), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.s_people_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!modelReady) {
            Text(stringResource(R.string.s_face_model_missing), color = MaterialTheme.colorScheme.error)
        } else {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        FaceScanner.scan(context, media) { done, total -> scope.launch { progress = done to total } }
                        index = withContext(Dispatchers.IO) { FaceStore.load(context) }
                        progress = null
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.s_scan_faces)) }
        }
        progress?.let { (done, total) -> Text(stringResource(R.string.s_scanning_progress, done, total)) }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { FaceStore.clear(context) }
                    index = FaceIndex()
                }
            },
        ) { Text(stringResource(R.string.s_clear_face_index)) }

        if (clusters.isEmpty()) {
            Text(stringResource(R.string.s_no_people), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(clusters) { i, cluster ->
                    val photos = cluster.map { it.uri }.distinct().mapNotNull { byUri[it] }.sortedBy { it.date }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                stringResource(R.string.s_person_photos, i + 1, photos.size),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Button(onClick = { onOpen(photos, 0) }, modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.s_open))
                            }
                        }
                    }
                }
            }
        }
    }
}
