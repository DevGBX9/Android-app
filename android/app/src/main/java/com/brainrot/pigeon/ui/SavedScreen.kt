package com.brainrot.pigeon.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.brainrot.pigeon.AppContainer
import com.brainrot.pigeon.R
import com.brainrot.pigeon.data.AvatarLoader
import com.brainrot.pigeon.data.SavedItem
import com.brainrot.pigeon.ui.theme.Accent
import com.brainrot.pigeon.ui.theme.Outline
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary
import com.brainrot.pigeon.ui.util.openExternal
import com.brainrot.pigeon.ui.util.toast

private val CellTop = Color(0xFF1C232B)
private val CellBottom = Color(0xFF0F141A)

// نصف قطر الريل: 9:16 مثل تبويب الريلز في إنستغرام.
private const val REEL_ASPECT = 9f / 16f
private const val GRID_COLUMNS = 3

/**
 * صفحة الريلز: شريط أيقونة الشبكة مثل إنستغرام ثم خانات ريلز قائمة (9:16)
 * بصور غلاف حقيقية من إنستغرام. كل ريل يُفتح بالضغط عليه.
 */
@Composable
fun ReelsRoute(container: AppContainer) {
    val viewModel: SavedViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SavedViewModel(container.saved) }
        },
    )
    val items by viewModel.items.collectAsStateWithLifecycle()
    val images = remember { container.reelImages }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ReelsHeader()
        HorizontalDivider(color = Outline, thickness = 1.dp)

        if (items.isEmpty()) {
            EmptyReels(Modifier.weight(1f))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.id }, contentType = { "reel" }) { item ->
                    ReelCell(
                        item = item,
                        images = images,
                        onClick = { openReel(context, item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReelsHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GridGlyph(color = TextPrimary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(7.dp))
            Box(
                Modifier
                    .width(54.dp)
                    .height(1.dp)
                    .background(TextPrimary),
            )
        }
    }
}

@Composable
private fun ReelCell(
    item: SavedItem,
    images: AvatarLoader,
    onClick: () -> Unit,
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, item.thumbnail) {
        value = item.thumbnail
            ?.takeIf { it.isNotBlank() }
            ?.let { url -> images.load(url, targetSize = 360) }
    }

    val bitmap = thumbnail

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(REEL_ASPECT)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.linearGradient(listOf(CellTop, CellBottom)))
            .clickable(onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = reelLabel(item),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // تعتيم سفلي حتى يبقى كود الريل واضحاً فوق الصورة.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.62f),
                            ),
                        ),
                    ),
            )
        } else {
            ReelGlyph(
                color = TextMuted.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(26.dp),
            )
        }

        if (item.registered) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.reels_registered),
                    tint = Accent,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        Text(
            text = reelLabel(item),
            color = if (bitmap != null) Color.White else TextMuted,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 6.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun EmptyReels(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ReelGlyph(color = TextMuted, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.reels_empty_title),
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = stringResource(R.string.reels_empty_body),
            color = TextMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** أيقونة شبكة ٣×٣ مرسومة، حتى لا نحتاج مكتبة أيقونات إضافية. */
@Composable
private fun GridGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / 3f
        val gap = cell * 0.16f

        for (row in 0..2) {
            for (column in 0..2) {
                drawRect(
                    color = color,
                    topLeft = Offset(column * cell + gap / 2f, row * cell + gap / 2f),
                    size = Size(cell - gap, cell - gap),
                )
            }
        }
    }
}

/** أيقونة ريل: مستطيل بزوايا دائرية بداخله مثلث تشغيل. */
@Composable
fun ReelGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = size.minDimension
        val stroke = side * 0.08f

        drawRoundRect(
            color = color,
            cornerRadius = CornerRadius(side * 0.24f),
            style = Stroke(stroke),
        )

        val play = Path().apply {
            moveTo(side * 0.38f, side * 0.30f)
            lineTo(side * 0.72f, side * 0.50f)
            lineTo(side * 0.38f, side * 0.70f)
            close()
        }

        drawPath(play, color)
    }
}

private fun reelLabel(item: SavedItem): String {
    val code = item.url
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }

    return code ?: item.title?.takeIf { it.isNotBlank() } ?: item.platform
}

private fun openReel(context: Context, item: SavedItem) {
    val url = item.url ?: item.remoteUrl
    if (url == null) {
        toast(context, context.getString(R.string.saved_no_content))
        return
    }
    if (!openExternal(context, url)) {
        toast(context, context.getString(R.string.saved_no_app))
    }
}
