package com.mygallery.app

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mygallery.app.pro.*

@Composable
fun ProToolsScreen(items:List<GalleryMedia>) {
    val groups=remember(items) { SmartGallery.timeline(items) }
    val storage=remember(items) { ProfessionalMediaTools.storageByFolder(items) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.s_professional_tools),style=MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.s_timeline_month_media_count),style=MaterialTheme.typography.titleMedium)
        groups.entries.take(24).forEach { Text("${it.key}: ${it.value.size}") }
        HorizontalDivider()
        Text(stringResource(R.string.s_storage_by_album),style=MaterialTheme.typography.titleMedium)
        storage.entries.sortedByDescending { it.value }.take(25).forEach { Text("${it.key}: ${it.value/1024/1024} MB") }
        HorizontalDivider()
        Text(stringResource(R.string.s_included_engines_exif_metadata_pagination_hsl_curves_masks_image_resize_export_watermark_similarity_hash_pdf_creation_local_settings_backup_video_trimming_is_available_from_the_trim_button_in_the_video_viewer))
        Text(stringResource(R.string.s_integration_pending_editing_screens_webdav_nextcloud_sync_ocr_and_object_ai_secure_portable_vault_backup_widgets_advanced_video_transitions_audio_mixing))
    }
}
