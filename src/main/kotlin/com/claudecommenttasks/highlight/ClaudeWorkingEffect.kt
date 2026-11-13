package com.claudecommenttasks.highlight

import com.claudecommenttasks.parser.ClaudeTask
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.CustomHighlighterOrder
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import javax.swing.Timer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Claude is thinking" effect on the comments of a running task: the comment text is blurred and a soft
 * band of light sweeps over the glyphs from left to right, repeating until [dispose] is called.
 *
 * Implementation: a range highlighter makes the real glyphs transparent, and its [CustomHighlighterRenderer]
 * (painted after the text) draws a Gaussian-blurred copy of the line plus the light band clipped to it.
 * A Swing timer repaints the affected lines at ~30 fps.
 */
class ClaudeWorkingEffect private constructor(
    private val document: Document,
    private val project: Project,
    private val highlighters: List<RangeHighlighter>,
) : Disposable {

    private val timer = Timer(FRAME_MS) { repaintEffectArea() }.apply { start() }

    private fun repaintEffectArea() {
        for (editor in EditorFactory.getInstance().getEditors(document, project)) {
            if (editor.isDisposed) continue
            val component = editor.contentComponent
            for (highlighter in highlighters) {
                if (!highlighter.isValid) continue
                val top = editor.offsetToXY(highlighter.startOffset).y
                val bottom = editor.offsetToXY(highlighter.endOffset).y + editor.lineHeight
                component.repaint(0, top, component.width, bottom - top)
            }
        }
    }

    @RequiresEdt
    override fun dispose() {
        timer.stop()
        highlighters.forEach { it.dispose() }
    }

    companion object {
        private const val FRAME_MS = 33

        /** Starts the effect on [ranges] (see [commentRanges]); returns null when there is nothing to show. */
        @RequiresEdt
        fun start(project: Project, document: Document, ranges: List<TextRange>): ClaudeWorkingEffect? {
            if (ranges.isEmpty()) return null
            val markup = DocumentMarkupModel.forDocument(document, project, true)
            val hideGlyphs = TextAttributes().apply { foregroundColor = TRANSPARENT }
            val highlighters = ranges.map { range ->
                markup.addRangeHighlighter(
                    range.startOffset,
                    range.endOffset,
                    HighlighterLayer.SELECTION - 1,
                    hideGlyphs,
                    HighlighterTargetArea.EXACT_RANGE,
                ).apply { customRenderer = BlurShimmerRenderer() }
            }
            return ClaudeWorkingEffect(document, project, highlighters)
        }

        /** Whole comments (not just the task text) containing the tasks, merged when they overlap. */
        @RequiresReadLock
        fun commentRanges(psiFile: PsiFile?, tasks: List<ClaudeTask>): List<TextRange> {
            val ranges = tasks.map { task ->
                val leaf = psiFile?.findElementAt(task.startOffset)
                PsiTreeUtil.getParentOfType(leaf, PsiComment::class.java, false)?.textRange
                    ?: TextRange(task.startOffset, task.endOffset)
            }.sortedBy { it.startOffset }
            val merged = ArrayList<TextRange>()
            for (range in ranges) {
                val last = merged.lastOrNull()
                if (last != null && range.startOffset <= last.endOffset) {
                    merged[merged.lastIndex] = last.union(range)
                } else {
                    merged += range
                }
            }
            return merged
        }

        private val TRANSPARENT = Color(0, 0, 0, 0)
    }
}

/** Paints the blurred text and the moving light band for one highlighter. Shared by all editors of the document. */
private class BlurShimmerRenderer : CustomHighlighterRenderer {

    private class CachedLine(val key: List<Any>, val image: BufferedImage)

    private val cache = HashMap<Int, CachedLine>()
    private var scratch: BufferedImage? = null
    private val startNanos = System.nanoTime()

    override fun getOrder(): CustomHighlighterOrder = CustomHighlighterOrder.AFTER_TEXT

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!highlighter.isValid) return
        val document = editor.document
        val start = highlighter.startOffset
        val end = min(highlighter.endOffset, document.textLength)
        if (start >= end) return

        val segments = lineSegments(document.charsSequence, document, start, end)
        if (segments.isEmpty()) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val scale = g2.transform.scaleX.takeIf { it > 0 } ?: 1.0
            val lineHeight = editor.lineHeight
            val blurRadius = max(2.0, lineHeight / 7.0)
            val pad = ceil(blurRadius * 2).toInt()

            val scheme = editor.colorsScheme
            val attributes = scheme.getAttributes(ClaudeTaskColors.TASK)
            val font = scheme.getFont(EditorFontType.forJavaStyle(attributes?.fontType ?: Font.PLAIN))
            val color = attributes?.foregroundColor ?: scheme.defaultForeground
            val tabSize = EditorUtil.getTabSize(editor)

            // One sweep spans the whole comment, so multi-line comments shimmer in sync.
            val positions = segments.map { editor.offsetToXY(it.startOffset) to editor.offsetToXY(it.endOffset).x }
            val left = positions.minOf { it.first.x }
            val right = positions.maxOf { it.second }
            val band = max(lineHeight * 5, 140)
            val travel = (right - left) + 2 * band
            val periodNanos = max(1_400_000_000L, travel * 1_000_000_000L / SWEEP_PX_PER_SECOND)
            val phase = ((System.nanoTime() - startNanos) % periodNanos).toDouble() / periodNanos
            val bandCenter = left - band + phase * travel

            val clip = g2.clipBounds
            for ((index, segment) in segments.withIndex()) {
                val (topLeft, endX) = positions[index]
                val y = topLeft.y
                if (clip != null && (y > clip.maxY || y + lineHeight < clip.minY)) continue
                val text = document.charsSequence.subSequence(segment.startOffset, segment.endOffset)
                    .toString().replace("\t", " ".repeat(tabSize))
                if (text.isBlank()) continue

                val key = listOf(text, font, color, scale, lineHeight, editor.ascent, endX - topLeft.x)
                val image = cache[index]?.takeIf { it.key == key }?.image
                    ?: renderBlurred(text, font, color, scale, lineHeight, editor.ascent, pad, blurRadius)
                        .also { cache[index] = CachedLine(key, it) }

                val lineGraphics = g2.create() as Graphics2D
                try {
                    // Stay inside the line so repaints of the line never leave blur residue behind.
                    lineGraphics.clipRect(topLeft.x - pad, y, (endX - topLeft.x) + 2 * pad, lineHeight)
                    val drawX = topLeft.x - pad
                    val drawW = image.width / scale
                    val drawH = image.height / scale
                    lineGraphics.drawImage(image, drawX, y - pad, drawW.roundToInt(), drawH.roundToInt(), null)
                    if (bandCenter + band / 2 >= drawX && bandCenter - band / 2 <= drawX + drawW) {
                        val glow = shimmer(image, ((bandCenter - drawX) * scale).toFloat(), (band * scale).toFloat())
                        lineGraphics.drawImage(glow, drawX, y - pad, drawW.roundToInt(), drawH.roundToInt(), null)
                    }
                } finally {
                    lineGraphics.dispose()
                }
            }
        } finally {
            g2.dispose()
        }
    }

    /** The light band, masked by the blurred glyphs (Apple-style text shimmer). */
    private fun shimmer(blurred: BufferedImage, centerX: Float, width: Float): BufferedImage {
        val buffer = scratch?.takeIf { it.width >= blurred.width && it.height >= blurred.height }
            ?: BufferedImage(blurred.width, blurred.height, BufferedImage.TYPE_INT_ARGB_PRE).also { scratch = it }
        val g = buffer.createGraphics()
        try {
            g.composite = AlphaComposite.Clear
            g.fillRect(0, 0, buffer.width, buffer.height)
            g.composite = AlphaComposite.Src
            g.drawImage(blurred, 0, 0, null)
            g.composite = AlphaComposite.SrcIn
            g.paint = LinearGradientPaint(
                centerX - width / 2, 0f, centerX + width / 2, 0f,
                floatArrayOf(0f, 0.5f, 1f),
                arrayOf(LIGHT_EDGE, LIGHT_CORE, LIGHT_EDGE),
            )
            g.fillRect(0, 0, blurred.width, blurred.height)
        } finally {
            g.dispose()
        }
        return if (buffer.width == blurred.width && buffer.height == blurred.height) buffer
        else buffer.getSubimage(0, 0, blurred.width, blurred.height)
    }

    private fun renderBlurred(
        text: String, font: Font, color: Color, scale: Double,
        lineHeight: Int, ascent: Int, pad: Int, radius: Double,
    ): BufferedImage {
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        val textWidth = try {
            probe.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            ceil(font.getStringBounds(text, probe.fontRenderContext).width).toInt()
        } finally {
            probe.dispose()
        }
        val width = ceil((textWidth + 2 * pad) * scale).toInt().coerceAtLeast(1)
        val height = ceil((lineHeight + 2 * pad) * scale).toInt().coerceAtLeast(1)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        try {
            g.scale(scale, scale)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.font = font
            g.color = color
            g.drawString(text, pad, pad + ascent)
        } finally {
            g.dispose()
        }
        return gaussianBlur(image, radius * scale)
    }

    private fun gaussianBlur(source: BufferedImage, radius: Double): BufferedImage {
        val size = (ceil(radius).toInt() * 2 + 1).coerceAtLeast(3)
        val sigma = radius / 2
        val half = size / 2
        val weights = FloatArray(size) { i -> exp(-((i - half) * (i - half)) / (2 * sigma * sigma)).toFloat() }
        val sum = weights.sum()
        for (i in weights.indices) weights[i] /= sum
        val horizontal = ConvolveOp(Kernel(size, 1, weights), ConvolveOp.EDGE_NO_OP, null)
        val vertical = ConvolveOp(Kernel(1, size, weights), ConvolveOp.EDGE_NO_OP, null)
        return vertical.filter(horizontal.filter(source, null), null)
    }

    /** Per logical line: the part of [start, end) on that line, without leading indentation. */
    private fun lineSegments(chars: CharSequence, document: Document, start: Int, end: Int): List<TextRange> {
        val result = ArrayList<TextRange>()
        for (line in document.getLineNumber(start)..document.getLineNumber(end)) {
            var segmentStart = max(start, document.getLineStartOffset(line))
            val segmentEnd = min(end, document.getLineEndOffset(line))
            while (segmentStart < segmentEnd && chars[segmentStart].isWhitespace()) segmentStart++
            if (segmentStart < segmentEnd) result += TextRange(segmentStart, segmentEnd)
        }
        return result
    }

    private companion object {
        const val SWEEP_PX_PER_SECOND = 420L
        val LIGHT_EDGE = Color(255, 255, 255, 0)
        val LIGHT_CORE = Color(255, 255, 255, 235)
    }
}
