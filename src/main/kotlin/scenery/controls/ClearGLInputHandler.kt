package scenery.controls

import cleargl.ClearGLWindow
import net.java.games.input.Component
import org.scijava.ui.behaviour.Behaviour
import org.scijava.ui.behaviour.BehaviourMap
import org.scijava.ui.behaviour.InputTriggerMap
import org.scijava.ui.behaviour.io.InputTriggerConfig
import org.scijava.ui.behaviour.io.yaml.YamlConfigIO
import scenery.Scene
import scenery.controls.behaviours.*
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.Reader
import java.io.StringReader

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */

class ClearGLInputHandler(scene: Scene, renderer: Any, window: ClearGLWindow) {
    protected val inputMap = InputTriggerMap()
    protected val behaviourMap = BehaviourMap()
    protected val handler: JOGLMouseAndKeyHandler

    protected val scene: Scene
    protected val renderer: Any
    protected val window: ClearGLWindow

    protected var config: InputTriggerConfig = InputTriggerConfig()

    init {
        // create Mouse & Keyboard Handler
        handler = JOGLMouseAndKeyHandler()
        handler.setInputMap(inputMap)
        handler.setBehaviourMap(behaviourMap)

        window.addKeyListener(handler)
        window.addMouseListener(handler)
        window.addWindowListener(handler)

        this.scene = scene
        this.renderer = renderer
        this.window = window
    }

    fun addBehaviour(behaviourName: String, behaviour: Behaviour) {
        behaviourMap.put(behaviourName, behaviour)
    }

    fun addKeyBinding(behaviourName: String, keys: String) {
        config.inputTriggerAdder(inputMap, "all").put(behaviourName, keys)
    }

    fun useDefaultBindings(bindingConfigFile: String) {
        // Load YAML config
        var reader: Reader

        try {
            reader = FileReader(bindingConfigFile)
        } catch (e: FileNotFoundException) {
            System.err.println("Falling back to default keybindings...")
            reader = StringReader("---\n" +
                    "- !mapping" + "\n" +
                    "  action: mouse_control" + "\n" +
                    "  contexts: [all]" + "\n" +
                    "  triggers: [button1, G]" + "\n" +
                    "- !mapping" + "\n" +
                    "  action: gamepad_movement_control" + "\n" +
                    "  contexts: [all]" + "\n" +
                    "  triggers: [button1]" + "\n" +
                    "- !mapping" + "\n" +
                    "  action: gamepad_camera_control" + "\n" +
                    "  contexts: [all]" + "\n" +
                    "  triggers: [P]" + "\n" +
                    "- !mapping" + "\n" +
                    "  action: scroll1" + "\n" +
                    "  contexts: [all]" + "\n" +
                    "  triggers: [scroll]" + "\n" +
                    "")
        }

        config = InputTriggerConfig(YamlConfigIO.read(reader))

        /*
     * Create behaviours and input mappings.
     */
        behaviourMap.put("mouse_control", FPSCameraControl("mouse_control", scene.findObserver(), window.width, window.height))
        behaviourMap.put("gamepad_camera_control", GamepadCameraControl("gamepad_camera_control", listOf(Component.Identifier.Axis.Z, Component.Identifier.Axis.RZ), scene.findObserver(), window.width, window.height))
        behaviourMap.put("gamepad_movement_control", GamepadMovementControl("gamepad_movement_control", listOf(Component.Identifier.Axis.X, Component.Identifier.Axis.Y), scene.findObserver()))

        behaviourMap.put("move_forward", MovementCommand("move_forward", "forward", scene.findObserver()))
        behaviourMap.put("move_back", MovementCommand("move_back", "back", scene.findObserver()))
        behaviourMap.put("move_left", MovementCommand("move_left", "left", scene.findObserver()))
        behaviourMap.put("move_right", MovementCommand("move_right", "right", scene.findObserver()))
        behaviourMap.put("move_up", MovementCommand("move_up", "up", scene.findObserver()))
        behaviourMap.put("move_down", MovementCommand("move_down", "down", scene.findObserver()))

        behaviourMap.put("move_forward_fast", MovementCommand("move_forward", "forward", scene.findObserver(), 1.0f))
        behaviourMap.put("move_back_fast", MovementCommand("move_back", "back", scene.findObserver(), 1.0f))
        behaviourMap.put("move_left_fast", MovementCommand("move_left", "left", scene.findObserver(), 1.0f))
        behaviourMap.put("move_right_fast", MovementCommand("move_right", "right", scene.findObserver(), 1.0f))
        behaviourMap.put("move_up_fast", MovementCommand("move_up", "up", scene.findObserver(), 1.0f))
        behaviourMap.put("move_down_fast", MovementCommand("move_down", "down", scene.findObserver(), 1.0f))

        behaviourMap.put("toggle_debug", ToggleCommand("toggle_debug", renderer, "toggleDebug"))
        behaviourMap.put("toggle_fullscreen", ToggleCommand("toggle_fullscreen", renderer, "toggleFullscreen"))
        behaviourMap.put("toggle_ssao", ToggleCommand("toggle_ssao", renderer, "toggleSSAO"))
        behaviourMap.put("toggle_hdr", ToggleCommand("toggle_hdr", renderer, "toggleHDR"))


        behaviourMap.put("increase_exposure", ToggleCommand("increase_exposure", renderer, "increaseExposure"))
        behaviourMap.put("decrease_exposure", ToggleCommand("decrease_exposure", renderer, "decreaseExposure"))
        behaviourMap.put("increase_gamma", ToggleCommand("increase_gamma", renderer, "increaseGamma"))
        behaviourMap.put("decrease_gamma", ToggleCommand("decrease_gamma", renderer, "decreaseGamma"))

        val adder = config.inputTriggerAdder(inputMap, "all")
        adder.put("mouse_control") // put input trigger as defined in config
        adder.put("gamepad_movement_control")
        adder.put("gamepad_camera_control")

        adder.put("move_forward", "W")
        adder.put("move_left", "A")
        adder.put("move_back", "S")
        adder.put("move_right", "D")

        adder.put("move_forward_fast", "shift W")
        adder.put("move_left_fast", "shift A")
        adder.put("move_back_fast", "shift S")
        adder.put("move_right_fast", "shift D")

        adder.put("move_up", "SPACE")
        adder.put("move_down", "shift SPACE")

        adder.put("toggle_debug", "Q")
        adder.put("toggle_fullscreen", "F")
        adder.put("toggle_ssao", "O")
        adder.put("toggle_hdr", "H")

        adder.put("increase_exposure", "K")
        adder.put("decrease_exposure", "L")
        adder.put("increase_gamma", "shift K")
        adder.put("decrease_gamma", "shift L")
    }
}
