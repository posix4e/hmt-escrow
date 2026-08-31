package org.hpb.cvat

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Frames for a live job, drawn rather than downloaded so a demo needs no
 * dataset. Shapes, not animals, because the labels have to be unambiguous:
 * the launcher commits their groundtruth hashes into the offer, so a worker
 * who labels correctly is paid and one who does not is genuinely not.
 */
object DemoFrames {
    /** Frame order is the groundtruth — index n shows [SHAPES]`[n]`. */
    val SHAPES = listOf("circle", "square", "triangle", "square", "circle", "triangle")

    val LABELS = listOf("circle", "square", "triangle")

    fun frames(): List<Pair<String, ByteArray>> =
        SHAPES.indices.map { "frame-$it.png" to png(it) }

    fun png(index: Int): ByteArray {
        val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xF7, 0xF5, 0xEF)
        g.fillRect(0, 0, SIZE, SIZE)
        g.color = Color(0x1D, 0x3A, 0x5F)
        draw(g, SHAPES[index])
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun draw(g: Graphics2D, shape: String) {
        val inset = SIZE / 5
        val span = SIZE - inset * 2
        when (shape) {
            "circle" -> g.fillOval(inset, inset, span, span)
            "square" -> g.fillRect(inset, inset, span, span)
            else -> g.fillPolygon(
                intArrayOf(SIZE / 2, inset, SIZE - inset),
                intArrayOf(inset, SIZE - inset, SIZE - inset),
                3,
            )
        }
    }

    private const val SIZE = 256
}
