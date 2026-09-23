package io.snailrun.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A stick figure doing [exercise], drawn from [ExercisePoses].
 *
 * [animate] runs the move on a loop, down and back up, at roughly the pace of a real
 * repetition; without it the figure holds the move's turning point, which is the pose
 * that tells a reader the most. Draws nothing for a move with no drawing.
 */
@Composable
fun ExerciseFigure(
    exercise: String,
    modifier: Modifier = Modifier,
    animate: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val move = ExercisePoses.forExercise(exercise) ?: return
    val t = if (animate) {
        val transition = rememberInfiniteTransition(label = "exercise")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "rep",
        )
        value
    } else {
        1f
    }
    val floor = MaterialTheme.colorScheme.outlineVariant
    val prop = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "$exercise, illustrated" },
    ) {
        drawFigure(move, t, color, floor, prop)
    }
}

private fun DrawScope.drawFigure(
    move: ExercisePoses.Move,
    t: Float,
    color: Color,
    floorColor: Color,
    propColor: Color,
) {
    val side = size.minDimension
    val stroke = (side * 0.035f).coerceAtLeast(1.5.dp.toPx())
    // Inset by a stroke so a head at the top edge or a toe at the side is not clipped.
    val scale = side - stroke * 2
    fun at(p: ExercisePoses.P) = Offset(stroke + p.x * scale, stroke + p.y * scale)

    val floorY = at(ExercisePoses.P(0f, ExercisePoses.FLOOR)).y
    move.prop?.let {
        val topLeft = at(ExercisePoses.P(it.left, it.top))
        val right = at(ExercisePoses.P(it.right, it.top)).x
        drawRect(propColor, topLeft, Size(right - topLeft.x, floorY - topLeft.y))
    }
    drawLine(floorColor, Offset(0f, floorY), Offset(size.width, floorY), strokeWidth = stroke * 0.6f)

    val pose = move.at(t)
    val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun chain(color: Color, vararg joints: ExercisePoses.P) {
        val path = Path()
        joints.forEachIndexed { i, joint ->
            val o = at(joint)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color, style = line)
    }

    // Far limbs first and faint, so the near ones cross in front of them.
    val far = color.copy(alpha = color.alpha * 0.4f)
    chain(far, pose.neck, pose.farElbow, pose.farHand)
    chain(far, pose.hip, pose.farKnee, pose.farAnkle, pose.farToe)

    chain(color, pose.hip, pose.nearKnee, pose.nearAnkle, pose.nearToe)
    chain(color, pose.neck, pose.hip)
    chain(color, pose.neck, pose.nearElbow, pose.nearHand)
    drawCircle(color, radius = HEAD_RADIUS * scale, center = at(pose.head), style = line)
}

private const val HEAD_RADIUS = 0.05f
