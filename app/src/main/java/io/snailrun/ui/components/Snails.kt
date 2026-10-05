package io.snailrun.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.snailrun.R
import io.snailrun.ui.theme.PersonTone
import io.snailrun.ui.theme.SnailTheme
import io.snailrun.ui.theme.SnailType

/**
 * A person on a shared session, drawn as the app's own snail in their colour.
 *
 * The runner's snail faces left, as it does on the tab bar; a partner's is mirrored, so
 * side by side the two face each other — which is the whole point of the session.
 */
@Composable
fun SnailAvatar(
    tone: PersonTone,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    mirrored: Boolean = false,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tone.fill.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_snail),
            contentDescription = contentDescription,
            tint = tone.fill,
            modifier = Modifier
                .size(size * 0.84f)
                .graphicsLayer { if (mirrored) scaleX = -1f },
        )
    }
}

/** The runner's snail. */
@Composable
fun YouSnail(modifier: Modifier = Modifier, size: Dp = 24.dp) =
    SnailAvatar(SnailTheme.you, modifier, size, contentDescription = "You")

/** A partner's snail, in the colour their id gives them. */
@Composable
fun PartnerSnail(partnerId: Int, name: String, modifier: Modifier = Modifier, size: Dp = 24.dp) =
    SnailAvatar(SnailTheme.person(partnerId), modifier, size, mirrored = true, contentDescription = name)

/** Two snails, nose to nose: a session run together. */
@Composable
fun SnailPair(partnerId: Int, name: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        PartnerSnail(partnerId, name, size = size)
        YouSnail(modifier = Modifier.offset(x = -(size * 0.12f)), size = size)
    }
}

/**
 * A name tag: the partner's snail and their name, in their colour.
 *
 * Shaped like a [Badge] so it sits in the same row as the session type, but coloured by
 * the person rather than by a tone — a person is not a kind of session.
 */
@Composable
fun PartnerTag(partnerId: Int, name: String, modifier: Modifier = Modifier) {
    val tone = SnailTheme.person(partnerId)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(tone.fill.copy(alpha = 0.14f))
            .padding(start = 2.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PartnerSnail(partnerId, name, size = 18.dp)
        Text(
            text = name.uppercase(),
            style = SnailType.metricCaption,
            color = tone.fill,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
