package org.ksdfv.thelema.boofcv
import boofcv.abst.fiducial.FiducialDetector
import boofcv.alg.distort.LensDistortionNarrowFOV
import boofcv.alg.distort.brown.LensDistortionBrown
import boofcv.factory.fiducial.ConfigFiducialBinary
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.factory.filter.binary.ConfigThreshold
import boofcv.factory.filter.binary.ThresholdType
import boofcv.io.calibration.CalibrationIO
import boofcv.struct.calib.CameraPinholeBrown
import boofcv.struct.image.GrayF32
import com.github.sarxos.webcam.Webcam
import georegression.struct.se.Se3_F64
import org.ksdfv.thelema.anim.AnimPlayer
import org.ksdfv.thelema.app.APP
import org.ksdfv.thelema.fs.FS
import org.ksdfv.thelema.g3d.IScene
import org.ksdfv.thelema.g3d.cam.ActiveCamera
import org.ksdfv.thelema.g3d.cam.Camera
import org.ksdfv.thelema.g3d.gltf.GLTF
import org.ksdfv.thelema.g3d.light.DirectionalLight
import org.ksdfv.thelema.g3d.node.ITransformNode
import org.ksdfv.thelema.g3d.node.Node
import org.ksdfv.thelema.gl.*
import org.ksdfv.thelema.img.Texture2D
import org.ksdfv.thelema.jvm.data.JvmByteBuffer
import org.ksdfv.thelema.lwjgl3.Lwjgl3App
import org.ksdfv.thelema.lwjgl3.Lwjgl3AppConf
import org.ksdfv.thelema.math.MATH
import org.ksdfv.thelema.math.Mat4
import org.ksdfv.thelema.math.Vec3
import org.ksdfv.thelema.mesh.ScreenQuad
import org.ksdfv.thelema.shader.post.PostShader
import java.io.InputStreamReader


object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val app = Lwjgl3App(Lwjgl3AppConf().apply {
            windowWidth = 1024
            windowHeight = 720
        })


        // === webcam image ===
        val webcam = Webcam.getDefault()

        // set max resolution
        webcam.viewSize = webcam.viewSizes.last()

        val camWidth = webcam.viewSize.width
        val camHeight = webcam.viewSize.height
        println("$camWidth x $camHeight")

        webcam.open()

        // get image bytes to create buffer, that will be updated in render loop
        val imageBuffer = webcam.imageBytes
        val bytes = JvmByteBuffer(imageBuffer)

        // create texture and upload initial image data
        val tex = Texture2D()
        tex.load(webcam.viewSize.width, webcam.viewSize.height, bytes, internalFormat = GL_RGB, pixelFormat = GL_RGB)


        // === BooCV ===
        // load the lens distortion parameters and the input image
        val param: CameraPinholeBrown = CalibrationIO.load(InputStreamReader(Main.javaClass.getResourceAsStream("/intrinsic.yaml")))
        val lensDistortion: LensDistortionNarrowFOV = LensDistortionBrown(param)

        // grayscale image for boofcv
        val grayData = FloatArray(camWidth * camHeight)
        val grayImage = GrayF32(camWidth, camHeight)
        grayImage.data = grayData

        // Detect the fiducial
        val detector: FiducialDetector<GrayF32> = FactoryFiducial.squareBinary(
            ConfigFiducialBinary(1.0), ConfigThreshold.local(ThresholdType.LOCAL_MEAN, 21), GrayF32::class.java
        )
        // ConfigFiducialBinary(0.1), ConfigThreshold.fixed(100),GrayF32.class)
        detector.setLensDistortion(lensDistortion, param.width, param.height)

        // transform data from detector
        val targetToSensor = Se3_F64()


        // === graphics ===
        val screenQuad = ScreenQuad()

        val imageRenderShader = PostShader(
            uvName = "uv",
            fragCode = """
varying vec2 uv;
uniform sampler2D tex;

void main() {
    gl_FragColor = texture2D(tex, vec2(uv.x, 1.0 - uv.y));
}
"""
        )
        val bgImgUnit = 0
        imageRenderShader["tex"] = bgImgUnit

        val rootNode = Node()
        var scene: IScene? = null
        var animPlayer: AnimPlayer? = null

        val light = DirectionalLight().apply {
            lightIntensity = 1f
            color.set(1f, 0.3f, 0f)
            direction.set(1f, -1f, 0f).nor()
        }

        val gltf = GLTF(FS.internal("nightshade/nightshade.gltf"))
        gltf.load {
            animPlayer = AnimPlayer()

            val nodes = ArrayList<ITransformNode>()
            gltf.nodes.forEach { nodes.add(it.node) }
            animPlayer?.nodes = nodes

            animPlayer?.setAnimation(gltf.animations.first { it.name == "idle" }.anim, -1)

            scene = gltf.scenes[0].scene
            scene?.lights?.add(light)

            rootNode.addChildren(scene!!.nodes)
        }

        // set initial position to invisible area
        rootNode.position.x = 1000f
        rootNode.updateTransform()

        val cam = Camera()
        cam.lookAt(Vec3(0f, 0f, -0.5f), Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f))
        cam.update()
        ActiveCamera.proxy = cam

        val worldMatrix = Mat4()
        worldMatrix.setToTranslation(1000f, 0f, 0f) // set initial position to invisible area

        GL.isDepthTestEnabled = true
        GL.glClearColor(1f, 0f, 0f, 1f)


        // render loop
        GL.render {
            GL.glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

            // render background webcam image
            imageRenderShader.bind()
            tex.bind(bgImgUnit)
            screenQuad.render(imageRenderShader)

            if (webcam.isImageNew) {
                // update background texture image
                webcam.getImageBytes(imageBuffer)
                GL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, tex.width, tex.height, 0, GL_RGB, GL_UNSIGNED_BYTE, bytes)

                // convert image to grayscale
                var byteCounter = 0
                for (i in grayData.indices) {
                    grayData[i] = (bytes[byteCounter].toInt() and 0xFF + bytes[byteCounter + 1].toInt() and 0xFF + bytes[byteCounter + 2].toInt() and 0xFF) / 3f
                    byteCounter += 3
                }

                // detect fiducials
                detector.detect(grayImage)
                for (i in 0 until detector.totalFound()) {
                    if (detector.is3D) {
                        detector.getFiducialToCamera(0, targetToSensor)

                        val mat = rootNode.worldMatrix
                        val r = targetToSensor.rotation
                        val t = targetToSensor.translation
                        mat.m00 = -r.get(0, 0).toFloat()
                        mat.m01 = -r.get(0, 1).toFloat()
                        mat.m02 = -r.get(0, 2).toFloat()
                        mat.m10 = -r.get(1, 0).toFloat()
                        mat.m11 = -r.get(1, 1).toFloat()
                        mat.m12 = -r.get(1, 2).toFloat()
                        mat.m20 = r.get(2, 0).toFloat()
                        mat.m21 = r.get(2, 1).toFloat()
                        mat.m22 = r.get(2, 2).toFloat()
                        mat.m03 = -t.x.toFloat()
                        mat.m13 = -t.y.toFloat()
                        mat.m23 = t.z.toFloat()
                        mat.rotate(1f, 0f, 0f, MATH.PI * 0.5f)

                        rootNode.worldMatrix.getRotation(rootNode.rotation)
                        rootNode.worldMatrix.getTranslation(rootNode.position)
                        rootNode.worldMatrix.getScale(rootNode.scale)
                        rootNode.updateTransform()
                    }
                }
            }

            // update model
            animPlayer?.update(APP.deltaTime)
            scene?.update(APP.deltaTime)
            rootNode.updateTransform()

            // render model
            // clear only depth, not color for overlay rendering
            GL.glClear(GL_DEPTH_BUFFER_BIT)
            scene?.render()
        }

        app.startLoop()
    }
}
