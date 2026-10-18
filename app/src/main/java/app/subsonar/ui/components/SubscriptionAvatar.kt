package app.subsonar.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import app.subsonar.R
import app.subsonar.domain.Avatar
import app.subsonar.domain.Visual
import app.subsonar.ui.theme.avatarColors

/** Drawable of each line-icon key in [app.subsonar.domain.SubscriptionVisuals.ICONS]. */
object SubscriptionIcons {
    val RES: Map<String, Int> = mapOf(
        "streaming" to R.drawable.ic_sub_streaming, "music" to R.drawable.ic_sub_music, "podcast" to R.drawable.ic_sub_podcast,
        "gaming" to R.drawable.ic_sub_gaming, "news" to R.drawable.ic_sub_news, "book" to R.drawable.ic_sub_book,
        "cloud" to R.drawable.ic_sub_cloud, "software" to R.drawable.ic_sub_software, "phone" to R.drawable.ic_sub_phone,
        "wifi" to R.drawable.ic_sub_wifi, "bolt" to R.drawable.ic_sub_bolt, "water" to R.drawable.ic_sub_water,
        "home" to R.drawable.ic_sub_home, "car" to R.drawable.ic_sub_car, "train" to R.drawable.ic_sub_train,
        "bike" to R.drawable.ic_sub_bike, "plane" to R.drawable.ic_sub_plane, "gym" to R.drawable.ic_sub_gym,
        "heart" to R.drawable.ic_sub_heart, "insurance" to R.drawable.ic_sub_insurance, "education" to R.drawable.ic_sub_education,
        "pet" to R.drawable.ic_sub_pet, "food" to R.drawable.ic_sub_food, "coffee" to R.drawable.ic_sub_coffee,
        "shopping" to R.drawable.ic_sub_shopping, "camera" to R.drawable.ic_sub_camera, "card" to R.drawable.ic_sub_card,
        "gift" to R.drawable.ic_sub_gift, "tools" to R.drawable.ic_sub_tools, "star" to R.drawable.ic_sub_star,
        "receipt" to R.drawable.ic_sub_receipt,
    )

    @DrawableRes fun res(key: String): Int = RES[key] ?: R.drawable.ic_sub_receipt
}

/** Rounded-square avatar: the subscription's line icon (or emoji) on a soft tint of its colour. */
@Composable
fun SubscriptionAvatar(visual: Visual, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val colors = avatarColors(visual.colorKey)
    Box(
        modifier.size(size).background(colors.container, RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        when (val a = visual.avatar) {
            is Avatar.Icon -> Icon(painterResource(SubscriptionIcons.res(a.key)), null, Modifier.size(size * 0.55f), tint = colors.content)
            is Avatar.Emoji -> Text(a.emoji, fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() }, maxLines = 1)
        }
    }
}
