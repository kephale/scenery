package scenery.rendermodules.opengl

import cleargl.*
import com.jogamp.common.nio.Buffers
import com.jogamp.opengl.GL
import com.jogamp.opengl.GL4
import coremem.types.NativeTypeEnum
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scenery.*
import scenery.controls.HMDInput
import scenery.fonts.SDFFontAtlas
import scenery.rendermodules.Renderer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */

/*
        the first texture units are reserved for the geometry buffer
     */


open class DeferredLightingRenderer : Renderer, Hubable {
    protected var logger: Logger = LoggerFactory.getLogger("DeferredLightingRenderer")
    protected var gl: GL4
    protected var width: Int
    protected var height: Int
    protected var geometryBuffer: ArrayList<GLFramebuffer>
    protected var hdrBuffer: ArrayList<GLFramebuffer>
    protected var combinationBuffer: ArrayList<GLFramebuffer>
    protected var lightingPassProgram: GLProgram
    protected var hdrPassProgram: GLProgram
    protected var combinerProgram: GLProgram

    protected var nodeStore = ConcurrentHashMap<String, Node>()
    protected var fontAtlas = HashMap<String, SDFFontAtlas>()

    var settings: Settings = Settings()
    override var hub: Hub? = null

    protected var textures = HashMap<String, GLTexture>()

    var eyes = (0..0)

    fun Boolean.toInt(): Int {
        if(this) {
            return 1
        } else {
            return 0
        }
    }

    constructor(gl: GL4, width: Int, height: Int) {
        this.gl = gl
        this.width = width
        this.height = height

        this.settings = this.getDefaultRendererSettings()

        gl.swapInterval = 0

        logger.info("DeferredLightingRenderer: $width x $height on ${gl.glGetString(GL.GL_RENDERER)}, ${gl.glGetString(GL.GL_VERSION)}")

        val numExtensionsBuffer = IntBuffer.allocate(1)
        gl.glGetIntegerv(GL4.GL_NUM_EXTENSIONS, numExtensionsBuffer)
        val extensions = (0..numExtensionsBuffer[0]-1).map { gl.glGetStringi(GL4.GL_EXTENSIONS, it) }
        logger.info("Supported extensions:\n${extensions.joinToString("\n\t")}")

        settings.set("ssao.FilterRadius", GLVector(5.0f/width, 5.0f/height))

        if(settings.get("vr.Active")) {
            eyes = (0..1)
            settings.set("vr.IPD", -0.5f)
        }

        geometryBuffer = ArrayList<GLFramebuffer>()
        hdrBuffer = ArrayList<GLFramebuffer>()
        combinationBuffer = ArrayList<GLFramebuffer>()

        eyes.forEach {
            // create 32bit position buffer, 16bit normal buffer, 8bit diffuse buffer and 24bit depth buffer
            val vrWidthDivisor = if(settings.get("vr.DoAnaglyph")) 1 else 2
            val actualWidth = if (settings.get("vr.Active")) this.width / vrWidthDivisor else this.width
            val actualHeight = if (settings.get("vr.Active")) this.height else this.height

            val gb = GLFramebuffer(this.gl, actualWidth, actualHeight)
            gb.addFloatRGBBuffer(this.gl, 32)
            gb.addFloatRGBBuffer(this.gl, 16)
            gb.addUnsignedByteRGBABuffer(this.gl, 8)
            gb.addDepthBuffer(this.gl, 24, 1)
            gb.checkDrawBuffers(this.gl)

            geometryBuffer.add(gb)


            // HDR buffers
            val hdrB = GLFramebuffer(this.gl, actualWidth, actualHeight)
            hdrB.addFloatRGBBuffer(this.gl, 32)
            hdrB.addFloatRGBBuffer(this.gl, 32)
            hdrB.checkDrawBuffers(this.gl)

            hdrBuffer.add(hdrB)

            val cb = GLFramebuffer(this.gl, actualWidth, actualHeight)
            cb.addUnsignedByteRGBABuffer(this.gl, 8)
            cb.checkDrawBuffers(this.gl)

            combinationBuffer.add(cb)
        }

        logger.info(geometryBuffer.map { it.toString() }.joinToString("\n"))

        lightingPassProgram = GLProgram.buildProgram(gl, DeferredLightingRenderer::class.java,
                arrayOf("shaders/FullscreenQuad.vert", "shaders/DeferredLighting.frag"))

        hdrPassProgram = GLProgram.buildProgram(gl, DeferredLightingRenderer::class.java,
                arrayOf("shaders/FullscreenQuad.vert", "shaders/HDR.frag"))

        combinerProgram = GLProgram.buildProgram(gl, DeferredLightingRenderer::class.java,
                arrayOf("shaders/FullscreenQuad.vert",  "shaders/Combiner.frag"))

        gl.glViewport(0, 0, this.width, this.height)
        gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)

        gl.glEnable(GL4.GL_TEXTURE_GATHER)
    }

    protected fun getDefaultRendererSettings(): Settings {
        val ds = Settings()

        ds.set("wantsFullscreen", false)
        ds.set("isFullscreen", false)

        ds.set("ssao.Active", true)
        ds.set("ssao.FilterRadius", GLVector(0.0f, 0.0f))
        ds.set("ssao.DistanceThreshold", 50.0f)
        ds.set("ssao.Algorithm", 1)

        ds.set("vr.Active", false)
        ds.set("vr.DoAnaglyph", false)
        ds.set("vr.IPD", 0.0f)
        ds.set("vr.EyeDivisor", 1)

        ds.set("hdr.Active", true)
        ds.set("hdr.Exposure", 1.0f)
        ds.set("hdr.Gamma", 2.2f)

        ds.set("debug.DebugDeferredBuffers", false)

        return ds
    }

    fun GLFramebuffer.textureTypeToUnit(type: String): Int {
        return this.boundBufferNum + when (type) {
            "ambient" -> 0
            "diffuse" -> 1
            "specular" -> 2
            "normal" -> 3
            "displacement" -> 4
            else -> {
                logger.warn("Unknown texture type $type"); 10
            }
        }
    }

    protected fun GeometryType.toOpenGLType(): Int {
        return when(this) {
            GeometryType.TRIANGLE_STRIP -> GL.GL_TRIANGLE_STRIP
            GeometryType.POLYGON -> GL.GL_TRIANGLES
            GeometryType.TRIANGLES -> GL.GL_TRIANGLES
            GeometryType.TRIANGLE_FAN -> GL.GL_TRIANGLE_FAN
            GeometryType.POINTS -> GL.GL_POINTS
            GeometryType.LINE -> GL.GL_LINE_STRIP
        }
    }


    fun toggleDebug() {
        if(settings.get<Boolean>("debug.DebugDeferredBuffers") == false) {
            settings.set("debug.DebugDeferredBuffers", true);
        } else {
            settings.set("debug.DebugDeferredBuffers", false);
        }
    }

    fun toggleSSAO() {
        if(settings.get<Boolean>("ssao.Active") == false) {
            settings.set("ssao.Active", true);
        } else {
            settings.set("ssao.Active", false);
        }
    }

    fun toggleHDR() {
        if(settings.get<Boolean>("hdr.Active") == false) {
            settings.set("hdr.Active", true);
        } else {
            settings.set("hdr.Active", false);
        }
    }

    fun increaseExposure() {
        val exp: Float = settings.get<Float>("hdr.Exposure")
        settings.set("hdr.Exposure", exp + 0.05f)
    }

    fun decreaseExposure() {
        val exp: Float = settings.get<Float>("hdr.Exposure")
        settings.set("hdr.Exposure", exp - 0.05f)
    }

    fun increaseGamma() {
        val gamma: Float = settings.get<Float>("hdr.Gamma")
        settings.set("hdr.Gamma", gamma + 0.05f)
    }

    fun decreaseGamma() {
        val gamma: Float = settings.get<Float>("hdr.Gamma")
        if(gamma - 0.05f >= 0) settings.set("hdr.Gamma", gamma - 0.05f)
    }

    fun toggleFullscreen() {
        if(!settings.get<Boolean>("wantsFullscreen")) {
            settings.set("wantsFullscreen", true)
        } else {
            settings.set("wantsFullscreen", false)
        }
    }

    fun getOpenGLObjectStateFromNode(node: Node): OpenGLObjectState {
        return node.metadata["DeferredLightingRenderer"] as OpenGLObjectState
    }

    override fun initializeScene(scene: Scene) {
        scene.discover(scene, { it is HasGeometry })
                .forEach { it ->
            it.metadata.put("DeferredLightingRenderer", OpenGLObjectState())
            initializeNode(it)
        }

        logger.info("Initialized ${textures.size} textures")
    }

    protected fun setMaterialUniformsForNode(n: Node, gl: GL4, s: OpenGLObjectState, program: GLProgram) {
        program.use(gl)
        program.getUniform("Material.Shininess").setFloat(0.001f);

        if (n.material != null) {
            program.getUniform("Material.Ka").setFloatVector(n.material!!.ambient);
            program.getUniform("Material.Kd").setFloatVector(n.material!!.diffuse);
            program.getUniform("Material.Ks").setFloatVector(n.material!!.specular);

            if(n.material!!.doubleSided) {
                gl.glDisable(GL.GL_CULL_FACE)
            }

            if(n.material!!.transparent) {
                gl.glEnable(GL.GL_BLEND)
                gl.glBlendFunc(GL.GL_SRC_COLOR, GL.GL_ONE_MINUS_SRC_ALPHA)
            } else {
                gl.glDisable(GL.GL_BLEND);
            }
        } else {
            program.getUniform("Material.Ka").setFloatVector3(n.position.toFloatBuffer());
            program.getUniform("Material.Kd").setFloatVector3(n.position.toFloatBuffer());
            program.getUniform("Material.Ks").setFloatVector3(n.position.toFloatBuffer());
        }

        s.textures.forEach { type, glTexture ->
            val samplerIndex = geometryBuffer.first().textureTypeToUnit(type)

            if(glTexture != null) {
                gl.glActiveTexture(GL.GL_TEXTURE0 + samplerIndex)
                gl.glBindTexture(GL.GL_TEXTURE_2D, glTexture.id)
                program.getUniform("ObjectTextures[" + (samplerIndex - geometryBuffer.first().boundBufferNum) + "]").setInt(samplerIndex)
            }
        }

        if(s.textures.size > 0){
            program.getUniform("materialType").setInt(1)
        }

        if(s.textures.containsKey("normal")) {
            program.getUniform("materialType").setInt(3)
        }
    }

    protected fun updateFontBoard(board: FontBoard) {
        val atlas = fontAtlas.getOrPut(board.fontName, { SDFFontAtlas(this.hub!!, board.fontName) })
        val m = atlas.createMeshForString(board.text)

        board.vertices = m.vertices
        board.normals = m.normals
        board.indices = m.indices
        board.texcoords = m.texcoords

        board.metadata.remove("DeferredLightingRenderer")
        board.metadata.put("DeferredLightingRenderer", OpenGLObjectState())
        initializeNode(board)

        val s = getOpenGLObjectStateFromNode(board)
        val texture = textures.getOrPut("sdf-${board.fontName}", {
            val t = GLTexture(gl, NativeTypeEnum.Float, 1,
                    atlas.atlasWidth,
                    atlas.atlasHeight,
                    1,
                    true,
                    1)

            t.setClamp(false, false);
            t.copyFrom(atlas.getAtlas(),
                    0,
                    true)
            t
        })
        s.textures.put("diffuse", texture)
    }

    protected fun preDrawAndUpdateGeometryForNode(n: Node) {
        if(n is HasGeometry) {
            n.preDraw()

            if(n.dirty) {
                if(n is FontBoard) {
                    updateFontBoard(n)
                }
                updateVertices(n)
                updateNormals(n)

                if (n.texcoords.size > 0) {
                    updateTextureCoords(n)
                }

                if (n.indices.size > 0) {
                    updateIndices(n)
                }

                n.dirty = false
            }
        }
    }


    override fun render(scene: Scene) {

        val renderOrderList = ArrayList<Node>()
        val cam: Camera = scene.findObserver()
        var mv: GLMatrix
        var mvp: GLMatrix
        var proj: GLMatrix

        val hmd: HMDInput? = if(hub!!.has(SceneryElement.HMDINPUT)
                && (hub!!.get(SceneryElement.HMDINPUT) as HMDInput).initializedAndWorking()) {
            hub!!.get(SceneryElement.HMDINPUT) as HMDInput
        } else {
            null
        }

        scene.discover(scene, { n -> n is Renderable && n is HasGeometry && n.visible }).forEach {
            renderOrderList.add(it)
        }

        // depth-sorting based on camera position
//        renderOrderList.sort {
//            a, b ->
//            (a.position.z() - b.position.z()).toInt()
//        }

        cam.view?.setCamera(cam.position, cam.position + cam.forward, cam.up)
//        cam.projection = GLMatrix().setPerspectiveProjectionMatrix(70.0f / 180.0f * Math.PI.toFloat(),
//                (1.0f * width) / (1.0f * height), 0.1f, 100000f)

        val instanceGroups = renderOrderList.groupBy { it.instanceOf }

//        System.err.println("Instance groups:\n${instanceGroups.keys.map {
//            key ->
//            "  <${key?.name}> " + instanceGroups.get(key)!!.map { it.name }.joinToString(", ") + "\n"
//        }.joinToString("")}")

        gl.glDisable(GL.GL_SCISSOR_TEST)
        gl.glDisable(GL4.GL_BLEND)
        gl.glEnable(GL.GL_DEPTH_TEST)
        gl.glViewport(0, 0, geometryBuffer.first().width, geometryBuffer.first().height)

        eyes.forEachIndexed { i, eye ->
            geometryBuffer[i].setDrawBuffers(gl)
            gl.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        }

        gl.glEnable(GL.GL_CULL_FACE)
        gl.glFrontFace(GL.GL_CCW)
        gl.glCullFace(GL.GL_BACK)
        gl.glDepthFunc(GL.GL_LEQUAL)

        instanceGroups[null]?.forEach nonInstancedDrawing@ { n ->
            if(n in instanceGroups.keys) {
                return@nonInstancedDrawing
            }

            if(!n.metadata.containsKey("DeferredLightingRenderer")) {
                n.metadata.put("DeferredLightingRenderer", OpenGLObjectState())
                initializeNode(n)
            }

            val s = getOpenGLObjectStateFromNode(n)
            n.updateWorld(true, false)

            if(n.material != null) {
                if(n.material?.needsTextureReload!!) {
                    n.material?.needsTextureReload = !loadTexturesForNode(n, s)
                }
            }

            if (n is Skybox) {
                gl.glCullFace(GL.GL_FRONT)
                gl.glDepthFunc(GL.GL_LEQUAL)
            }

            eyes.forEachIndexed { i, eye ->
                var projection: GLMatrix = if(hmd == null) {
                    GLMatrix().setPerspectiveProjectionMatrix(70.0f / 180.0f * Math.PI.toFloat(),
                        (1.0f * geometryBuffer[i].width) / (1.0f * geometryBuffer[i].height), 0.1f, 100000f)
                } else {
                    hmd?.getEyeProjection(i)
                }

                mv = if(hmd == null ) {
                    GLMatrix.getTranslation(settings.get<Float>("vr.IPD") * -1.0f * Math.pow(-1.0, 1.0*i).toFloat(), 0.0f, 0.0f).transpose()
                } else {
                    hmd?.getHeadToEyeTransform(i).clone()
                }
                val pose = if(hmd == null) {
                    GLMatrix.getIdentity()
                } else {
                    hmd.getPose()
                }
                mv.mult(pose)
                mv.mult(cam.view!!)
                mv.mult(cam.rotation)
                mv.mult(n.world)

                mvp = projection.clone()
                mvp.mult(mv)

                s.program?.let { program ->
                    program.use(gl)
                    program.getUniform("ModelMatrix")!!.setFloatMatrix(n.model, false);
                    program.getUniform("ModelViewMatrix")!!.setFloatMatrix(mv, false)
                    program.getUniform("ProjectionMatrix")!!.setFloatMatrix(projection, false)
                    program.getUniform("MVP")!!.setFloatMatrix(mvp, false)
                    program.getUniform("isBillboard")!!.setInt(n.isBillboard.toInt())

                    setMaterialUniformsForNode(n, gl, s, program)
                }

                preDrawAndUpdateGeometryForNode(n)
                geometryBuffer[i].setDrawBuffers(gl)
                drawNode(n)
            }
        }

        instanceGroups.keys.filterNotNull().forEach instancedDrawing@ { n ->
            var start = System.nanoTime()

            if(!n.metadata.containsKey("DeferredLightingRenderer")) {
                n.metadata.put("DeferredLightingRenderer", OpenGLObjectState())
                initializeNode(n)
            }

            val s = getOpenGLObjectStateFromNode(n)
            val instances = instanceGroups[n]!!

            logger.trace("${n.name} has additional instance buffers: ${s.additionalBufferIds.keys}")
            logger.trace("${n.name} instancing: Instancing group size is ${instances.size}")

            val matrixSize = 4*4
            val models = ArrayList<Float>()
            val modelviews = ArrayList<Float>()
            val modelviewprojs = ArrayList<Float>()

            mv = GLMatrix.getIdentity()
            proj = GLMatrix.getIdentity()
            mvp = GLMatrix.getIdentity()
            var mo = GLMatrix.getIdentity()

            instances.forEach { node -> node.updateWorld(true, false) }

            eyes.forEach {
                eye ->

                models.clear()
                modelviews.clear()
                modelviewprojs.clear()

                models.ensureCapacity(matrixSize * instances.size)
                modelviews.ensureCapacity(matrixSize * instances.size)
                modelviewprojs.ensureCapacity(matrixSize * instances.size)

                instances.forEachIndexed { i, node ->
                    val projection = if(hmd == null) {
                        GLMatrix().setPerspectiveProjectionMatrix(70.0f / 180.0f * Math.PI.toFloat(),
                                (1.0f * geometryBuffer[eye].width) / (1.0f * geometryBuffer[eye].height), 0.1f, 100000f)
                    } else {
                        hmd.getEyeProjection(eye)
                    }

                    mo = node.world.clone()

                    mv = if(hmd == null) {
                        GLMatrix.getTranslation(settings.get<Float>("vr.IPD") * -1.0f * Math.pow(-1.0, 1.0*eye).toFloat(), 0.0f, 0.0f).transpose()
                    } else {
                        hmd.getHeadToEyeTransform(eye).clone()
                    }

                    val pose = if(hmd == null) {
                        GLMatrix.getIdentity()
                    } else {
                        hmd.getPose()
                    }
                    mv.mult(pose)
                    mv.mult(cam.view!!)
                    mv.mult(cam.rotation)
                    mv.mult(node.world)

                    mvp = projection.clone()
                    mvp.mult(mv)

                    models.addAll(mo.floatArray.asSequence())
                    modelviews.addAll(mv.floatArray.asSequence())
                    modelviewprojs.addAll(mvp.floatArray.asSequence())
                }


                logger.trace("${n.name} instancing: Collected ${modelviewprojs.size / matrixSize} MVPs in ${(System.nanoTime() - start) / 10e6}ms")

                // bind instance buffers
                start = System.nanoTime()
                val matrixSizeBytes: Long = 1L * Buffers.SIZEOF_FLOAT * matrixSize * instances.size

                gl.gL4.glBindVertexArray(s.mVertexArrayObject[0])

                gl.gL4.glBindBuffer(GL.GL_ARRAY_BUFFER, s.additionalBufferIds["Model"]!!);
                gl.gL4.glBufferData(GL.GL_ARRAY_BUFFER, matrixSizeBytes,
                        FloatBuffer.wrap(models.toFloatArray()), GL.GL_DYNAMIC_DRAW);

                gl.gL4.glBindBuffer(GL.GL_ARRAY_BUFFER, s.additionalBufferIds["ModelView"]!!);
                gl.gL4.glBufferData(GL.GL_ARRAY_BUFFER, matrixSizeBytes,
                        FloatBuffer.wrap(modelviews.toFloatArray()), GL.GL_DYNAMIC_DRAW);

                gl.gL4.glBindBuffer(GL.GL_ARRAY_BUFFER, s.additionalBufferIds["MVP"]!!);
                gl.gL4.glBufferData(GL.GL_ARRAY_BUFFER, matrixSizeBytes,
                        FloatBuffer.wrap(modelviewprojs.toFloatArray()), GL.GL_DYNAMIC_DRAW);

                logger.trace("${n.name} instancing: Updated matrix buffers in ${(System.nanoTime() - start) / 10e6}ms")

                s.program?.let {
                    setMaterialUniformsForNode(n, gl, s, it)
                }

                preDrawAndUpdateGeometryForNode(n)

                geometryBuffer[eye].setDrawBuffers(gl)
                drawNodeInstanced(n, count = instances.size)
            }
        }

        val lights = scene.discover(scene, { it is PointLight })

        eyes.forEachIndexed { i, eye ->
            lightingPassProgram.bind()

            lightingPassProgram.getUniform("numLights").setInt(lights.size)
            lightingPassProgram.getUniform("ProjectionMatrix").setFloatMatrix(cam.projection!!.clone(), false)
            lightingPassProgram.getUniform("InverseProjectionMatrix").setFloatMatrix(cam.projection!!.clone().invert(), false)

            for (i in 0..lights.size - 1) {
                lightingPassProgram.getUniform("lights[$i].Position").setFloatVector(lights[i].position)
                lightingPassProgram.getUniform("lights[$i].Color").setFloatVector((lights[i] as PointLight).emissionColor)
                lightingPassProgram.getUniform("lights[$i].Intensity").setFloat((lights[i] as PointLight).intensity)
                lightingPassProgram.getUniform("lights[$i].Linear").setFloat((lights[i] as PointLight).linear)
                lightingPassProgram.getUniform("lights[$i].Quadratic").setFloat((lights[i] as PointLight).quadratic)
            }

            lightingPassProgram.getUniform("gPosition").setInt(0)
            lightingPassProgram.getUniform("gNormal").setInt(1)
            lightingPassProgram.getUniform("gAlbedoSpec").setInt(2)
            lightingPassProgram.getUniform("gDepth").setInt(3)

            lightingPassProgram.getUniform("debugDeferredBuffers").setInt(settings.get<Boolean>("debug.DebugDeferredBuffers").toInt())
            lightingPassProgram.getUniform("ssao_filterRadius").setFloatVector(settings.get<GLVector>("ssao.FilterRadius"))
            lightingPassProgram.getUniform("ssao_distanceThreshold").setFloat(settings.get<Float>("ssao.DistanceThreshold"))
            lightingPassProgram.getUniform("doSSAO").setInt(settings.get<Boolean>("ssao.Active").toInt())

            geometryBuffer[i].bindTexturesToUnitsWithOffset(gl, 0)
            hdrBuffer[i].setDrawBuffers(gl)


            gl.glViewport(0, 0, hdrBuffer[i].width, hdrBuffer[i].height)

            gl.glDisable(GL.GL_CULL_FACE)
            gl.glDisable(GL.GL_BLEND)
            gl.glDisable(GL.GL_DEPTH_TEST)
            gl.glPointSize(1.5f)
            gl.glEnable(GL4.GL_PROGRAM_POINT_SIZE)

            if (!settings.get<Boolean>("hdr.Active")) {
                if(settings.get<Boolean>("vr.DoAnaglyph")) {
                    if(i == 0) {
                        gl.glColorMask(true, false, false, false)
                    } else {
                        gl.glColorMask(false, true, true, false)
                    }
                }
                combinationBuffer[i].setDrawBuffers(gl)
                gl.glClear(GL4.GL_COLOR_BUFFER_BIT)
                gl.glViewport(0, 0, combinationBuffer[i].width, combinationBuffer[i].height)

                renderFullscreenQuad(lightingPassProgram)
            } else {
                gl.glClear(GL4.GL_COLOR_BUFFER_BIT or GL4.GL_DEPTH_BUFFER_BIT)
                // render to the active, eye-dependent HDR buffer
                renderFullscreenQuad(lightingPassProgram)

                hdrBuffer[i].bindTexturesToUnitsWithOffset(gl, 0)
                combinationBuffer[i].setDrawBuffers(gl)
                gl.glViewport(0, 0, combinationBuffer[i].width, combinationBuffer[i].height)
                gl.glClear(GL4.GL_COLOR_BUFFER_BIT or GL4.GL_DEPTH_BUFFER_BIT)

                if(settings.get<Boolean>("vr.DoAnaglyph")) {
                    if(i == 0) {
                        gl.glColorMask(true, false, false, false)
                    } else {
                        gl.glColorMask(false, true, true, false)
                    }
                }

                hdrPassProgram.getUniform("Gamma").setFloat(settings.get<Float>("hdr.Gamma"))
                hdrPassProgram.getUniform("Exposure").setFloat(settings.get<Float>("hdr.Exposure"))
                renderFullscreenQuad(hdrPassProgram)
            }
        }

        combinationBuffer.first().revertToDefaultFramebuffer(gl)
        if(settings.get<Boolean>("vr.DoAnaglyph")) {
            gl.glColorMask(true, true, true, true)
        }
        gl.glClear(GL4.GL_COLOR_BUFFER_BIT or GL4.GL_DEPTH_BUFFER_BIT)
        gl.glDisable(GL4.GL_SCISSOR_TEST)
        gl.glViewport(0, 0, width, height)
        gl.glScissor(0, 0, width, height)

        if (settings.get<Boolean>("vr.Active")) {
            if(settings.get<Boolean>("vr.DoAnaglyph")) {
                combinerProgram.getUniform("vrActive").setInt(0)
                combinerProgram.getUniform("anaglyphActive").setInt(1)
            } else {
                combinerProgram.getUniform("vrActive").setInt(1)
                combinerProgram.getUniform("anaglyphActive").setInt(0)
            }
            combinationBuffer[0].bindTexturesToUnitsWithOffset(gl, 0)
            combinationBuffer[1].bindTexturesToUnitsWithOffset(gl, 4)
            combinerProgram.getUniform("leftEye").setInt(0)
            combinerProgram.getUniform("rightEye").setInt(4)
            renderFullscreenQuad(combinerProgram)

            if(hmd != null && hmd.hasCompositor()) {
                logger.trace("Submitting to compositor...")
                hmd?.submitToCompositor(combinationBuffer[0].getTextureIds(gl)[0], combinationBuffer[1].getTextureIds(gl)[0])
            }
        } else {
            combinationBuffer.first().bindTexturesToUnitsWithOffset(gl, 0)
            combinerProgram.getUniform("leftEye").setInt(0)
            combinerProgram.getUniform("rightEye").setInt(0)
            combinerProgram.getUniform("vrActive").setInt(0)
            renderFullscreenQuad(combinerProgram)
        }
    }

    fun renderFullscreenQuadOld(quadGenerator: GLProgram) {
        val quadId: IntBuffer = IntBuffer.allocate(1)

        quadGenerator.gl.gL4.glGenVertexArrays(1, quadId)
        quadGenerator.gl.gL4.glBindVertexArray(quadId.get(0))

        // fake call to draw one point, geometry is generated in shader pipeline
        quadGenerator.gl.gL4.glDrawArrays(GL.GL_POINTS, 0, 1)
        quadGenerator.gl.gL4.glBindTexture(GL.GL_TEXTURE_2D, 0)

        quadGenerator.gl.gL4.glBindVertexArray(0)
        quadGenerator.gl.gL4.glDeleteVertexArrays(1, quadId)
    }

    fun renderFullscreenQuad(quadGenerator: GLProgram) {
        var quad: Node
        val quadName = "fullscreenQuad-${quadGenerator.id}"

        if(!nodeStore.containsKey(quadName)) {
            quad = Plane(GLVector(1.0f, 1.0f, 0.0f))
            val material = OpenGLMaterial()

            material.program = quadGenerator

            quad.material = material
            quad.metadata.put("DeferredLightingRenderer", OpenGLObjectState())
            initializeNode(quad)

            nodeStore.put(quadName, quad)
        } else {
            quad = nodeStore[quadName]!!
        }

        (quad.material as OpenGLMaterial).program = quadGenerator

        drawNode(quad)
        quadGenerator.gl.gL4.glBindTexture(GL.GL_TEXTURE_2D, 0)
    }

    fun initializeNode(node: Node): Boolean {
        var s: OpenGLObjectState

        if (node.instanceOf == null) {
            s = node.metadata["DeferredLightingRenderer"] as OpenGLObjectState
        } else {
            s = node.instanceOf!!.metadata["DeferredLightingRenderer"] as OpenGLObjectState
            node.metadata["DeferredLightingRenderer"] = s

            if (!s.initialized) {
                logger.trace("Instance not yet initialized, doing now...")
                initializeNode(node.instanceOf!!)
            }

            if (!s.additionalBufferIds.containsKey("Model") || !s.additionalBufferIds.containsKey("ModelView") || !s.additionalBufferIds.containsKey("MVP")) {
                logger.trace("${node.name} triggered instance buffer creation")
                createInstanceBuffer(node.instanceOf!!)
                logger.trace("---")
            }
            return true
        }

        if (s.initialized) {
            return true
        }

        // generate VAO for attachment of VBO and indices
        gl.gL3.glGenVertexArrays(1, s.mVertexArrayObject, 0)

        // generate three VBOs for coords, normals, texcoords
        gl.glGenBuffers(3, s.mVertexBuffers, 0)
        gl.glGenBuffers(1, s.mIndexBuffer, 0)

        if (node.material == null || node.material !is OpenGLMaterial || (node.material as OpenGLMaterial).program == null) {
            if (node.useClassDerivedShader) {
                val javaClass = node.javaClass.simpleName
                val className = javaClass.substring(javaClass.indexOf(".") + 1)

                val shaders = arrayOf(".vert", ".geom", ".tese", ".tesc", ".frag", ".comp")
                        .map { "shaders/$className$it" }
                        .filter {
                            DeferredLightingRenderer::class.java.getResource(it) != null
                        }

                s.program = GLProgram.buildProgram(gl, DeferredLightingRenderer::class.java,
                        shaders.toTypedArray())
            } else if (node.metadata.filter { it.value is OpenGLShaderPreference }.isNotEmpty()) {
//                val prefs = node.metadata.first { it is OpenGLShaderPreference } as OpenGLShaderPreference
                val prefs = node.metadata["ShaderPreference"] as OpenGLShaderPreference

                if (prefs.parameters.size > 0) {
                    s.program = GLProgram.buildProgram(gl, node.javaClass,
                            prefs.shaders.toTypedArray(), prefs.parameters)
                } else {
                    try {
                        s.program = GLProgram.buildProgram(gl, node.javaClass,
                                prefs.shaders.toTypedArray())
                    } catch(e: NullPointerException) {
                        s.program = GLProgram.buildProgram(gl, this.javaClass,
                                prefs.shaders.map { System.err.println(it); "shaders/" + it }.toTypedArray())
                    }

                }
            } else {
                s.program = GLProgram.buildProgram(gl, DeferredLightingRenderer::class.java,
                        arrayOf("shaders/DefaultDeferred.vert", "shaders/DefaultDeferred.frag"))
            }
        } else {
            s.program = (node.material as OpenGLMaterial).program
        }

        if (node is HasGeometry) {
            setVerticesAndCreateBufferForNode(node)
            setNormalsAndCreateBufferForNode(node)

            if (node.texcoords.size > 0) {
                setTextureCoordsAndCreateBufferForNode(node)
            }

            if (node.indices.size > 0) {
                setIndicesAndCreateBufferForNode(node)
            }
        }

        loadTexturesForNode(node, s);
        s.initialized = true
        return true
    }

    fun <K, V> HashMap<K, V>.forEachParallel(maxThreads: Int = 5, action: ((K, V) -> Unit)) {
        val iterator = this.asSequence().iterator()
        var threadCount = 0

        while(iterator.hasNext()) {
            val current = iterator.next()

            thread {
                threadCount++
                while(threadCount > maxThreads) {
                    Thread.sleep(50)
                }

                action.invoke(current.key, current.value)
                threadCount--
            }
        }
    }

    protected fun loadTexturesForNode(node: Node, s: OpenGLObjectState): Boolean {
        if(node.lock.tryLock()) {
            node.material?.textures?.forEach {
                type, texture ->
                if (!textures.containsKey(texture) || node.material?.needsTextureReload!!) {
                    logger.trace("Loading texture $texture for ${node.name}")
                    var glTexture = if (texture.startsWith("fromBuffer:")) {
                        val gt = node.material!!.transferTextures!!.get(texture.substringAfter("fromBuffer:"))

                        val t = GLTexture(gl, gt!!.type, gt!!.channels,
                                gt!!.dimensions.x().toInt(),
                                gt!!.dimensions.y().toInt(),
                                1,
                                true,
                                1)

                        t.setClamp(!gt.repeatS, !gt.repeatT);
                        t.copyFrom(gt!!.contents)
                        t
                    } else {
                        GLTexture.loadFromFile(gl, texture, true, 1)
                    }

                    s.textures.put(type, glTexture)
                    textures.put(texture, glTexture)
                } else {
                    s.textures.put(type, textures[texture]!!)
                }
            }

            s.initialized = true
            node.lock.unlock()
            return true
        } else {
            return false
        }
    }

    fun reshape(newWidth: Int, newHeight: Int) {
        this.width = newWidth
        this.height = newHeight

        if (settings.get<Boolean>("vr.Active")) {
            val eyeDivisor = settings.get<Int>("vr.EyeDivisor")
            geometryBuffer.forEach {
                it.resize(gl, newWidth / eyeDivisor, newHeight)
            }

            hdrBuffer.forEach {
                it.resize(gl, newWidth / eyeDivisor, newHeight)
            }
        } else {
            geometryBuffer.first().resize(gl, newWidth, newHeight)
            hdrBuffer.first().resize(gl, newWidth, newHeight)
        }

        gl.glClear(GL.GL_DEPTH_BUFFER_BIT or GL.GL_COLOR_BUFFER_BIT)
        gl.glViewport(0, 0, this.width, this.height)
    }

    private fun createInstanceBuffer(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)

        val matrixSize = 4*4
        val vectorSize = 4
        val locationBase = 3
        val matrices = arrayOf("Model", "ModelView", "MVP")
        val i = IntArray(matrices.size)

        gl.gL4.glBindVertexArray(s.mVertexArrayObject[0])
        gl.gL4.glGenBuffers(matrices.size, i, 0)

        i.forEachIndexed { locationOffset, bufferId ->
            s.additionalBufferIds.put(matrices[locationOffset], bufferId)

            gl.gL4.glBindBuffer(GL.GL_ARRAY_BUFFER, bufferId)

            for (offset in 0..3) {
                val l = locationBase + locationOffset*vectorSize + offset

                val pointerOffsetBytes: Long = 1L * Buffers.SIZEOF_FLOAT * offset * vectorSize
                val matrixSizeBytes = matrixSize * Buffers.SIZEOF_FLOAT

                gl.gL4.glEnableVertexAttribArray(l)
                gl.gL4.glVertexAttribPointer(l, vectorSize, GL.GL_FLOAT, false,
                        matrixSizeBytes, pointerOffsetBytes)
                gl.gL4.glVertexAttribDivisor(l, 1)
            }
        }

        gl.gL4.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
        gl.gL4.glBindVertexArray(0)
    }

    fun setVerticesAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node);
        val pVertexBuffer: FloatBuffer = FloatBuffer.wrap((node as HasGeometry).vertices)

        s.mStoredPrimitiveCount = pVertexBuffer.remaining() / node.vertexSize

        gl.gL3.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, s.mVertexBuffers[0])

        gl.gL3.glEnableVertexAttribArray(0)
        gl.glBufferData(GL.GL_ARRAY_BUFFER,
                (pVertexBuffer.limit() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
                pVertexBuffer,
                if (s.isDynamic)
                    GL.GL_DYNAMIC_DRAW
                else
                    GL.GL_STATIC_DRAW)

        gl.gL3.glVertexAttribPointer(0,
                node.vertexSize,
                GL.GL_FLOAT,
                false,
                0,
                0)

        gl.gL3.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
    }

    fun updateVertices(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pVertexBuffer: FloatBuffer = FloatBuffer.wrap((node as HasGeometry).vertices)

        s.mStoredPrimitiveCount = pVertexBuffer.remaining() / node.vertexSize

        gl.gL3.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, s.mVertexBuffers[0])

        gl.gL3.glEnableVertexAttribArray(0)
        gl.glBufferData(GL.GL_ARRAY_BUFFER,
                (pVertexBuffer.limit() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
                pVertexBuffer,
                GL.GL_DYNAMIC_DRAW)

        gl.gL3.glVertexAttribPointer(0,
                node.vertexSize,
                GL.GL_FLOAT,
                false,
                0,
                0)

        gl.gL3.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
    }

    fun setNormalsAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node);
        val pNormalBuffer: FloatBuffer = FloatBuffer.wrap((node as HasGeometry).normals)

        gl.gL3.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, s.mVertexBuffers[1])

        if(pNormalBuffer.limit() > 0) {
            gl.gL3.glEnableVertexAttribArray(1)
            gl.glBufferData(GL.GL_ARRAY_BUFFER,
                    (pNormalBuffer.limit() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
                    pNormalBuffer,
                    if (s.isDynamic)
                        GL.GL_DYNAMIC_DRAW
                    else
                        GL.GL_STATIC_DRAW)

            gl.gL3.glVertexAttribPointer(1,
                    node.vertexSize,
                    GL.GL_FLOAT,
                    false,
                    0,
                    0)

        }
        gl.gL3.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
    }

    fun updateNormals(node: Node) {
        val s = getOpenGLObjectStateFromNode(node);
        val pNormalBuffer: FloatBuffer = FloatBuffer.wrap((node as HasGeometry).normals)

        gl.gL3.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, s.mVertexBuffers[1])

        gl.gL3.glEnableVertexAttribArray(1)
        gl.glBufferSubData(GL.GL_ARRAY_BUFFER,
                0,
                (pNormalBuffer.limit() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
                pNormalBuffer)

        gl.gL3.glVertexAttribPointer(1,
                node.vertexSize,
                GL.GL_FLOAT,
                false,
                0,
                0)

        gl.gL3.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
    }

    fun setTextureCoordsAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node);
        val pTextureCoordsBuffer: FloatBuffer = FloatBuffer.wrap((node as HasGeometry).texcoords)

        gl.gL3.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, s.mVertexBuffers[2])

        gl.gL3.glEnableVertexAttribArray(2)
        gl.glBufferData(GL.GL_ARRAY_BUFFER,
                (pTextureCoordsBuffer.limit() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
                pTextureCoordsBuffer,
                if (s.isDynamic)
                    GL.GL_DYNAMIC_DRAW
                else
                    GL.GL_STATIC_DRAW)

        gl.gL3.glVertexAttribPointer(2,
                node.texcoordSize,
                GL.GL_FLOAT,
                false,
                0,
                0)

        gl.gL3.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
    }

    fun updateTextureCoords(node: Node) {
        val s = getOpenGLObjectStateFromNode(node);
        val pTextureCoordsBuffer: FloatBuffer = FloatBuffer.wrap((node as HasGeometry).texcoords)

        gl.gL3.glBindVertexArray(s.mVertexArrayObject[0])
        gl.gL3.glBindBuffer(GL.GL_ARRAY_BUFFER,
                s.mVertexBuffers[2])

        gl.gL3.glEnableVertexAttribArray(2)
        gl.glBufferSubData(GL.GL_ARRAY_BUFFER,
                0,
                (pTextureCoordsBuffer.limit() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
                pTextureCoordsBuffer)

        gl.gL3.glVertexAttribPointer(2,
                node.texcoordSize,
                GL.GL_FLOAT,
                false,
                0,
                0)

        gl.gL3.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
    }

    fun setIndicesAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node);
        val pIndexBuffer: IntBuffer = IntBuffer.wrap((node as HasGeometry).indices)

        s.mStoredIndexCount = pIndexBuffer.remaining()

        gl.gL3.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, s.mIndexBuffer[0])

        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER,
                (pIndexBuffer.limit() * (Integer.SIZE / java.lang.Byte.SIZE)).toLong(),
                pIndexBuffer,
                if (s.isDynamic)
                    GL.GL_DYNAMIC_DRAW
                else
                    GL.GL_STATIC_DRAW)

        gl.gL3.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun updateIndices(node: Node) {
        val s = getOpenGLObjectStateFromNode(node);
        val pIndexBuffer: IntBuffer = IntBuffer.wrap((node as HasGeometry).indices)

        s.mStoredIndexCount = pIndexBuffer.remaining()

        gl.gL3.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, s.mIndexBuffer[0])

        gl.glBufferSubData(GL.GL_ELEMENT_ARRAY_BUFFER,
                0,
                (pIndexBuffer.limit() * (Integer.SIZE / java.lang.Byte.SIZE)).toLong(),
                pIndexBuffer)

        gl.gL3.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun drawNode(node: Node, offset: Int = 0) {
        val s = getOpenGLObjectStateFromNode(node);

        s.program?.use(gl)

        gl.gL3.glBindVertexArray(s.mVertexArrayObject[0])

        if (s.mStoredIndexCount > 0) {
            gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER,
                    s.mIndexBuffer[0])
            gl.glDrawElements((node as HasGeometry).geometryType.toOpenGLType(),
                    s.mStoredIndexCount,
                    GL.GL_UNSIGNED_INT,
                    offset.toLong())

            gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, 0)
        } else {
            gl.glDrawArrays((node as HasGeometry).geometryType.toOpenGLType(), offset, s.mStoredPrimitiveCount)
        }

        gl.gL3.glUseProgram(0)
        gl.gL4.glBindVertexArray(0)
    }

    fun drawNodeInstanced(node: Node, count: Int, offset: Long = 0) {
        val s = getOpenGLObjectStateFromNode(node);

        s.program?.use(gl)

        gl.gL4.glBindVertexArray(s.mVertexArrayObject[0])

        if(s.mStoredIndexCount > 0) {
            gl.gL4.glDrawElementsInstanced(
                    (node as HasGeometry).geometryType.toOpenGLType(),
                    s.mStoredIndexCount,
                    GL.GL_UNSIGNED_INT,
                    offset,
                    count)
        } else {
            gl.gL4.glDrawArraysInstanced(
                    (node as HasGeometry).geometryType.toOpenGLType(),
                    0, s.mStoredPrimitiveCount, count);

        }

        gl.gL4.glUseProgram(0)
        gl.gL4.glBindVertexArray(0)
    }
}