package scenery

import scenery.rendermodules.opengl.OpenGLShaderPreference
import java.util.*

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class FontBoard(var fontName: String = "Source Code Pro", override var isBillboard: Boolean = true) : Mesh() {
    var text: String = ""
        set(value) {
            dirty = true
            field = value
        }

    init {
        name = "FontBoard"
        metadata.put(
                "ShaderPreference",
                OpenGLShaderPreference(
                        arrayListOf("DefaultDeferred.vert", "FontBoard.frag"),
                        HashMap<String, String>(),
                        arrayListOf("DeferredShadingRenderer")))
    }
}