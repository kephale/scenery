package scenery

import cleargl.GLVector
import scenery.rendermodules.opengl.OpenGLShaderPreference
import java.util.*

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class FontBoard(font: String = "Source Code Pro", override var isBillboard: Boolean = true) : Mesh() {
    var text: String = ""
        set(value) {
            dirty = true
            field = value
        }

    var fontName: String = "Source Code Pro"
        set(value) {
            dirty = true
            field = value
        }

    var color: GLVector = GLVector(0.5f, 0.5f, 0.5f)

    init {
        name = "FontBoard"
        fontName = font
        metadata.put(
                "ShaderPreference",
                OpenGLShaderPreference(
                        arrayListOf("DefaultDeferred.vert", "FontBoard.frag"),
                        HashMap<String, String>(),
                        arrayListOf("DeferredShadingRenderer")))
    }

    override fun toString(): String {
        return "FontBoard ($fontName): $text"
    }
}