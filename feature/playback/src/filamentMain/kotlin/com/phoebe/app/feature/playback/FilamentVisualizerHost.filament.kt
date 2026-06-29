package com.phoebe.app.feature.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import io.github.erkko68.filament.Box as FilamentBox
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.IndexBuffer
import io.github.erkko68.filament.Material
import io.github.erkko68.filament.MaterialInstance
import io.github.erkko68.filament.RenderableManager
import io.github.erkko68.filament.VertexBuffer
import io.github.erkko68.filament.compose.FilamentSceneScope
import io.github.erkko68.filament.compose.FilamentSceneView
import io.github.erkko68.filament.compose.LocalFilamentEngine
import io.github.erkko68.filament.compose.LocalFilamentScene
import io.github.erkko68.filament.compose.orbitGestures
import io.github.erkko68.filament.compose.rememberOrbitCameraState
import io.github.erkko68.filament.compose.scene.AntiAliasing
import io.github.erkko68.filament.compose.scene.Bloom
import io.github.erkko68.filament.compose.scene.Color as FilamentColor
import io.github.erkko68.filament.compose.scene.Direction
import io.github.erkko68.filament.compose.scene.Exposure
import io.github.erkko68.filament.compose.scene.PostProcessing
import io.github.erkko68.filament.compose.scene.Position
import io.github.erkko68.filament.compose.scene.Projection
import io.github.erkko68.filament.compose.scene.SkyboxSource
import io.github.erkko68.filament.compose.scene.rememberCameraState
import io.github.erkko68.filament.compose.scene.rememberSkyboxState
import io.github.erkko68.filament.filamat.Filamat
import io.github.erkko68.filament.filamat.MaterialBuilder
import io.github.erkko68.filament.toBytes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

@Composable
internal actual fun FilamentVisualizerHost(
    preset: NowPlayingVisualizerPreset,
    renderState: AudioVisualizerRenderState,
    isPlaying: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier,
    fallback: @Composable (Modifier) -> Unit,
) {
    Box(modifier.background(Color.Black)) {
        FilamentWireframeSpectrumHost(
            preset = preset,
            renderState = renderState,
            isPlaying = isPlaying,
            motionEnabled = motionEnabled,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FilamentWireframeSpectrumHost(
    preset: NowPlayingVisualizerPreset,
    renderState: AudioVisualizerRenderState,
    isPlaying: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier,
) {
    val style = remember(preset) { preset.wireframeStyle() }
    val cameraState = rememberCameraState(
        eye = Position(style.cameraX, style.cameraY, style.cameraZ),
        target = Position(0f, 0f, 0f),
        up = Direction(0f, 1f, 0f),
        projection = Projection.Perspective(fovDegrees = style.fovDegrees, near = 0.05, far = 80.0),
        exposure = Exposure(aperture = 2.8f, shutterSpeed = 1f / 60f, sensitivity = 520f),
    )
    val orbitState = rememberOrbitCameraState(
        cameraState = cameraState,
        zoomSpeed = 0.018f,
        orbitSpeedX = 0.010f,
        orbitSpeedY = 0.010f,
        enablePanning = true,
    )
    val skybox = rememberSkyboxState(
        source = SkyboxSource.Color(FilamentColor(0f, 0f, 0f), alpha = 1f),
    )
    val postProcessing = remember(style) {
        PostProcessing(
            bloom = Bloom(strength = style.bloomStrength),
            antiAliasing = AntiAliasing(fxaaEnabled = true),
        )
    }

    FilamentSceneView(
        modifier = modifier
            .semantics { contentDescription = "Wireframe Spectrum 3D visualizer" }
            .onSizeChanged { orbitState.setViewport(it.width, it.height) }
            .orbitGestures(orbitState),
        cameraState = cameraState,
        skyboxState = skybox,
        postProcessing = postProcessing,
    ) {
        val material = rememberWireframeMaterial() ?: return@FilamentSceneView
        WireframeSpectrumLines(
            renderState = renderState,
            style = style,
            material = material,
            isPlaying = isPlaying,
            motionEnabled = motionEnabled,
        )
    }
}

@Composable
private fun rememberWireframeMaterial(): Material? {
    val engine = LocalFilamentEngine.current
    val material = remember(engine) {
        runCatching {
            Filamat.init()
            val materialPackage = MaterialBuilder()
                .name("PhoebeWireframeSpectrum")
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .blending(MaterialBuilder.BlendingMode.ADD)
                .culling(MaterialBuilder.CullingMode.NONE)
                .depthWrite(false)
                .depthCulling(false)
                .doubleSided(true)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "color")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "intensity")
                .platform(MaterialBuilder.Platform.ALL)
                .targetApi(MaterialBuilder.TargetApi.ALL)
                .material(
                    """
                    void material(inout MaterialInputs material) {
                        prepareMaterial(material);
                        material.baseColor = materialParams.color;
                        material.emissive = vec4(materialParams.color.rgb * materialParams.intensity, materialParams.color.a);
                    }
                    """.trimIndent(),
                )
                .build()
            if (!materialPackage.isValid()) {
                null
            } else {
                Material.Builder()
                    .payload(materialPackage.getBuffer())
                    .build(engine)
            }
        }.getOrNull()
    }

    DisposableEffect(material) {
        onDispose {
            if (material != null) {
                engine.destroyMaterial(material)
            }
        }
    }

    return material
}

@Composable
private fun FilamentSceneScope.WireframeSpectrumLines(
    renderState: AudioVisualizerRenderState,
    style: WireframeVisualizerStyle,
    material: Material,
    isPlaying: Boolean,
    motionEnabled: Boolean,
) {
    val brightness = style.brightnessBase + renderState.envelope * if (isPlaying || motionEnabled) {
        style.brightnessReactive
    } else {
        style.brightnessIdle
    }
    val mesh = renderState.mesh.toFilamentWireframeGroups(style)
    val centerMaterial = rememberWireframeMaterialInstance(
        material = material,
        color = style.centerColor,
        intensity = brightness * style.centerIntensity,
    )
    val greenMaterial = rememberWireframeMaterialInstance(
        material = material,
        color = style.peakColor,
        intensity = brightness * style.peakIntensity,
    )
    val cyanMaterial = rememberWireframeMaterialInstance(
        material = material,
        color = style.upperColor,
        intensity = brightness * style.upperIntensity,
    )
    val blueMaterial = rememberWireframeMaterialInstance(
        material = material,
        color = style.traceColor,
        intensity = brightness * style.traceIntensity,
    )
    val roseMaterial = rememberWireframeMaterialInstance(
        material = material,
        color = style.lowerColor,
        intensity = brightness * style.lowerIntensity,
    )

    WireframeLineRenderable(mesh.center, centerMaterial)
    WireframeLineRenderable(mesh.greenPeaks, greenMaterial)
    WireframeLineRenderable(mesh.cyanUpper, cyanMaterial)
    WireframeLineRenderable(mesh.blueTrace, blueMaterial)
    WireframeLineRenderable(mesh.roseLower, roseMaterial)
}

@Composable
private fun rememberWireframeMaterialInstance(
    material: Material,
    color: WireframeColor,
    intensity: Float,
): MaterialInstance {
    val engine = LocalFilamentEngine.current
    val instance = remember(material, color) {
        material.createInstance().also {
            it.setParameter("color", color.r, color.g, color.b, color.a)
        }
    }
    SideEffect {
        instance.setParameter("intensity", intensity)
    }
    DisposableEffect(instance) {
        onDispose { engine.destroyMaterialInstance(instance) }
    }
    return instance
}

@Composable
private fun FilamentSceneScope.WireframeLineRenderable(
    mesh: FilamentLineMesh,
    material: MaterialInstance,
) {
    if (mesh.positions.isEmpty() || mesh.indices.isEmpty()) return

    val engine = LocalFilamentEngine.current
    val scene = LocalFilamentScene.current
    val vertexCount = mesh.positions.size / FloatComponentsPerPosition
    val indexCount = mesh.indices.size
    val buffers = remember(engine, vertexCount, indexCount) { mesh.upload(engine) }
    SideEffect {
        buffers.vertexBuffer.setBufferAt(engine, 0, mesh.positions.toBytes())
    }
    DisposableEffect(buffers) {
        onDispose {
            engine.destroyVertexBuffer(buffers.vertexBuffer)
            engine.destroyIndexBuffer(buffers.indexBuffer)
        }
    }

    val entity = remember(buffers, material) {
        engine.getEntityManager().create().also { entity ->
            RenderableManager.Builder(1)
                .geometry(0, RenderableManager.PrimitiveType.LINES, buffers.vertexBuffer, buffers.indexBuffer)
                .material(0, material)
                .boundingBox(WireframeSpectrumBounds)
                .culling(false)
                .castShadows(false)
                .receiveShadows(false)
                .priority(0)
                .build(engine, entity)
        }
    }

    DisposableEffect(entity) {
        scene.addEntity(entity)
        onDispose {
            scene.removeEntity(entity)
            engine.getRenderableManager().destroy(entity)
            engine.getEntityManager().destroy(entity)
        }
    }
}

private fun FilamentLineMesh.upload(engine: Engine): FilamentLineBuffers {
    val vertexBuffer = VertexBuffer.Builder()
        .vertexCount(positions.size / FloatComponentsPerPosition)
        .bufferCount(1)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3)
        .build(engine)
    vertexBuffer.setBufferAt(engine, 0, positions.toBytes())

    val indexBuffer = IndexBuffer.Builder()
        .indexCount(indices.size)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)
    indexBuffer.setBuffer(engine, indices.toBytes())

    return FilamentLineBuffers(vertexBuffer, indexBuffer)
}

private fun WireframeSpectrumMesh.toFilamentWireframeGroups(style: WireframeVisualizerStyle): FilamentWireframeGroups {
    val center = FilamentLineBuilder()
    val greenPeaks = FilamentLineBuilder()
    val cyanUpper = FilamentLineBuilder()
    val blueTrace = FilamentLineBuilder()
    val roseLower = FilamentLineBuilder()

    centerSegments.forEachIndexed { index, segment ->
        if (index % style.centerStride == 0) {
            center.addCenterSegment(this, segment, style)
        }
    }

    horizontalSegments.forEachIndexed { index, segment ->
        if (index % style.horizontalStride != 0 || !segment.visibleIn(this, style)) return@forEachIndexed
        val from = vertices[segment.from]
        val to = vertices[segment.to]
        val averageX = (from.x + to.x) * 0.5f
        val isUpper = from.y > 0f && to.y > 0f
        val isLower = from.y < 0f && to.y < 0f
        when {
            isLower -> roseLower.addSegment(this, segment, style)
            isUpper && averageX.isPeakLobeColumn(style) -> greenPeaks.addSegment(this, segment, style)
            index % style.accentStride == 0 -> cyanUpper.addSegment(this, segment, style)
            else -> blueTrace.addSegment(this, segment, style)
        }
    }
    diagonalSegments.forEachIndexed { index, segment ->
        if (index % style.diagonalStride != 0 || !segment.visibleIn(this, style)) return@forEachIndexed
        val from = vertices[segment.from]
        val to = vertices[segment.to]
        val averageX = (from.x + to.x) * 0.5f
        val isUpper = from.y > 0f && to.y > 0f
        val isLower = from.y < 0f && to.y < 0f
        when {
            isLower && index % 2 == 0 -> roseLower.addSegment(this, segment, style)
            isUpper && averageX.isPeakLobeColumn(style) -> greenPeaks.addSegment(this, segment, style)
            index % style.accentStride == 0 -> cyanUpper.addSegment(this, segment, style)
            else -> blueTrace.addSegment(this, segment, style)
        }
    }

    return FilamentWireframeGroups(
        center = center.build(),
        greenPeaks = greenPeaks.build(),
        cyanUpper = cyanUpper.build(),
        blueTrace = blueTrace.build(),
        roseLower = roseLower.build(),
    )
}

private class FilamentLineBuilder {
    private val positions = FloatBuilder()
    private val indices = IntBuilder()

    fun addSegment(mesh: WireframeSpectrumMesh, segment: WireframeSegment, style: WireframeVisualizerStyle) {
        val startIndex = positions.size / FloatComponentsPerPosition
        addVertex(mesh.vertices[segment.from], style)
        addVertex(mesh.vertices[segment.to], style)
        indices += startIndex
        indices += startIndex + 1
    }

    fun addCenterSegment(mesh: WireframeSpectrumMesh, segment: WireframeSegment, style: WireframeVisualizerStyle) {
        val startIndex = positions.size / FloatComponentsPerPosition
        addWorldVertex(mesh.vertices[segment.from].x.toCenterWorld(style))
        addWorldVertex(mesh.vertices[segment.to].x.toCenterWorld(style))
        indices += startIndex
        indices += startIndex + 1
    }

    fun build(): FilamentLineMesh {
        if (positions.isEmpty()) {
            return FilamentLineMesh(FloatArray(0), IntArray(0))
        }
        return FilamentLineMesh(
            positions = positions.toFloatArray(),
            indices = indices.toIntArray(),
        )
    }

    private fun addVertex(vertex: WireframeVertex, style: WireframeVisualizerStyle) {
        addWorldVertex(vertex.toWorld(style))
    }

    private fun addWorldVertex(point: WorldPoint) = addWorldVertex(point.x, point.y, point.z)

    private fun addWorldVertex(x: Float, y: Float, z: Float) {
        positions += x
        positions += y
        positions += z
    }
}

private class FloatBuilder(initialCapacity: Int = 1024) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    fun isEmpty(): Boolean = size == 0

    operator fun plusAssign(value: Float) {
        ensureCapacity(size + 1)
        data[size] = value
        size += 1
    }

    fun toFloatArray(): FloatArray = if (size == data.size) data else data.copyOf(size)

    private fun ensureCapacity(capacity: Int) {
        if (capacity <= data.size) return
        data = data.copyOf((data.size * 2).coerceAtLeast(capacity))
    }
}

private class IntBuilder(initialCapacity: Int = 1024) {
    private var data = IntArray(initialCapacity)
    var size = 0
        private set

    operator fun plusAssign(value: Int) {
        ensureCapacity(size + 1)
        data[size] = value
        size += 1
    }

    fun toIntArray(): IntArray = if (size == data.size) data else data.copyOf(size)

    private fun ensureCapacity(capacity: Int) {
        if (capacity <= data.size) return
        data = data.copyOf((data.size * 2).coerceAtLeast(capacity))
    }
}

private data class WireframeColor(
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float,
)

private data class FilamentWireframeGroups(
    val center: FilamentLineMesh,
    val greenPeaks: FilamentLineMesh,
    val cyanUpper: FilamentLineMesh,
    val blueTrace: FilamentLineMesh,
    val roseLower: FilamentLineMesh,
)

private data class FilamentLineMesh(
    val positions: FloatArray,
    val indices: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        other is FilamentLineMesh &&
            positions.contentEquals(other.positions) &&
            indices.contentEquals(other.indices)

    override fun hashCode(): Int = 31 * positions.contentHashCode() + indices.contentHashCode()
}

private data class FilamentLineBuffers(
    val vertexBuffer: VertexBuffer,
    val indexBuffer: IndexBuffer,
)

private const val FloatComponentsPerPosition = 3
private const val FullTurnRadians = (PI * 2.0).toFloat()

private val WireframeSpectrumBounds = FilamentBox(
    0f,
    0f,
    0f,
    5.2f,
    4.0f,
    5.2f,
)

private fun Float.isPeakLobeColumn(style: WireframeVisualizerStyle): Boolean {
    val distanceFromCenter = abs((this - 0.5f) * 2f)
    return distanceFromCenter in style.peakRange
}

private fun WireframeSegment.visibleIn(mesh: WireframeSpectrumMesh, style: WireframeVisualizerStyle): Boolean {
    if (style.edgeTrim <= 0f) return true
    val from = mesh.vertices[from]
    val to = mesh.vertices[to]
    val averageX = (from.x + to.x) * 0.5f
    return averageX in style.edgeTrim..(1f - style.edgeTrim)
}

private fun WireframeVertex.toWorld(style: WireframeVisualizerStyle): WorldPoint {
    val x = this.x
    val y = this.y
    val z = this.z
    val recency = 1f - z
    val height = y * (style.yScale + recency * style.recencyYScale)
    val sheetX = (x - 0.5f) * style.xScale
    val depth = (0.5f - z) * style.zScale
    val distanceFromCenter = abs((x - 0.5f) * 2f)

    return when (style.geometry) {
        WireframeGeometry.Sheet -> WorldPoint(sheetX, height, depth)
        WireframeGeometry.Canyon -> WorldPoint(
            sheetX * 0.92f,
            height * 1.18f,
            depth * 0.82f + y.sign() * 0.24f,
        )
        WireframeGeometry.Tunnel -> {
            val angle = x * FullTurnRadians
            val radius = (1.25f + y * 0.72f + recency * 0.18f).coerceAtLeast(0.42f)
            WorldPoint(cosF(angle) * radius, sinF(angle) * radius, depth * 1.16f)
        }
        WireframeGeometry.Halo -> {
            val angle = x * FullTurnRadians
            val radius = (1.55f + recency * 0.62f + y * 0.58f).coerceAtLeast(0.38f)
            WorldPoint(cosF(angle) * radius, depth * 0.34f, sinF(angle) * radius)
        }
        WireframeGeometry.Spiral -> {
            val angle = x * FullTurnRadians * 1.55f + z * 2.7f
            val radius = 0.55f + distanceFromCenter * 1.55f + recency * 0.36f
            WorldPoint(cosF(angle) * radius, height * 0.92f, sinF(angle) * radius + depth * 0.36f)
        }
        WireframeGeometry.Aurora -> WorldPoint(
            sheetX,
            height * 0.78f + sinF(x * FullTurnRadians * 1.4f + z * 3.6f) * 0.28f,
            depth * 0.92f + cosF(x * FullTurnRadians * 1.8f + z * 2.2f) * 0.42f,
        )
        WireframeGeometry.Crystal -> {
            val shard = if (((x * 24f).toInt() + (z * 18f).toInt()) % 2 == 0) 0.18f else -0.18f
            WorldPoint(
                sheetX * 0.94f + shard * 0.38f,
                y.sign() * abs(y).pow(0.72f) * (style.yScale + recency * 1.0f),
                depth * 0.78f + shard,
            )
        }
        WireframeGeometry.Fan -> {
            val angle = (x - 0.5f) * PI.toFloat() * 0.94f
            val radius = 1.28f + z * 2.65f
            WorldPoint(sinF(angle) * radius, height * 0.96f, cosF(angle) * radius - 1.85f)
        }
        WireframeGeometry.Ribbon -> WorldPoint(
            sheetX,
            height * 0.72f + sinF(x * FullTurnRadians * 2f + z * 4.2f) * 0.22f,
            depth * 0.62f + cosF(x * FullTurnRadians * 1.25f) * 0.72f,
        )
        WireframeGeometry.Kaleidoscope -> {
            val spokeAngle = x * FullTurnRadians * 2f
            val radius = 0.45f + distanceFromCenter * 1.55f + abs(y) * 0.45f
            WorldPoint(cosF(spokeAngle) * radius, height * 0.7f, sinF(spokeAngle) * radius + depth * 0.2f)
        }
        WireframeGeometry.Starfield -> {
            val jitter = deterministicJitter(x, z)
            WorldPoint(
                sheetX * 0.82f + jitter.x * 0.36f,
                height * 0.52f + jitter.y * 0.5f,
                depth * 0.9f + jitter.z * 0.72f,
            )
        }
    }
}

private fun Float.toCenterWorld(style: WireframeVisualizerStyle): WorldPoint {
    val sheetX = (this - 0.5f) * style.xScale
    return when (style.geometry) {
        WireframeGeometry.Tunnel,
        WireframeGeometry.Halo,
        WireframeGeometry.Spiral,
        WireframeGeometry.Kaleidoscope,
        -> {
            val angle = this * FullTurnRadians
            WorldPoint(cosF(angle) * 0.58f, 0f, sinF(angle) * 0.58f)
        }
        WireframeGeometry.Fan -> {
            val angle = (this - 0.5f) * PI.toFloat() * 0.94f
            WorldPoint(sinF(angle) * 1.1f, 0f, cosF(angle) * 1.1f - 1.25f)
        }
        else -> WorldPoint(sheetX, 0f, 0f)
    }
}

private fun NowPlayingVisualizerPreset.wireframeStyle(): WireframeVisualizerStyle =
    when (this) {
        NowPlayingVisualizerPreset.CanyonWire3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Canyon,
            xScale = 5.8f,
            yScale = 2.2f,
            zScale = 2.2f,
            edgeTrim = 0.075f,
            bloomStrength = 0.10f,
            peakColor = WireframeColor(0.58f, 1f, 0.42f, 0.45f),
            lowerColor = WireframeColor(1f, 0.42f, 0.48f, 0.38f),
        )
        NowPlayingVisualizerPreset.PulseTunnel3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Tunnel,
            xScale = 4.8f,
            yScale = 1.9f,
            zScale = 3.6f,
            edgeTrim = 0.0f,
            diagonalStride = 2,
            bloomStrength = 0.16f,
            upperColor = WireframeColor(0.28f, 0.95f, 1f, 0.42f),
            traceColor = WireframeColor(0.68f, 0.54f, 1f, 0.36f),
            lowerColor = WireframeColor(1f, 0.78f, 0.22f, 0.34f),
            cameraY = 0.8f,
            cameraZ = 6.7f,
        )
        NowPlayingVisualizerPreset.OrbitalHalo3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Halo,
            xScale = 4.5f,
            yScale = 1.65f,
            zScale = 3.0f,
            edgeTrim = 0.0f,
            horizontalStride = 2,
            bloomStrength = 0.13f,
            peakColor = WireframeColor(1f, 1f, 1f, 0.48f),
            upperColor = WireframeColor(0.48f, 0.68f, 1f, 0.38f),
            lowerColor = WireframeColor(1f, 0.62f, 0.62f, 0.34f),
            cameraY = 2.1f,
            cameraZ = 6.4f,
        )
        NowPlayingVisualizerPreset.SpiralGalaxy3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Spiral,
            xScale = 4.7f,
            yScale = 2.1f,
            zScale = 2.8f,
            edgeTrim = 0.025f,
            bloomStrength = 0.15f,
            peakColor = WireframeColor(0.72f, 0.58f, 1f, 0.46f),
            upperColor = WireframeColor(0.4f, 1f, 0.78f, 0.36f),
            traceColor = WireframeColor(1f, 0.84f, 0.34f, 0.32f),
            cameraY = 1.8f,
            cameraZ = 6.8f,
        )
        NowPlayingVisualizerPreset.AuroraRibbon3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Aurora,
            xScale = 6.2f,
            yScale = 1.8f,
            zScale = 2.4f,
            edgeTrim = 0.07f,
            diagonalStride = 2,
            bloomStrength = 0.18f,
            peakColor = WireframeColor(0.42f, 1f, 0.46f, 0.44f),
            upperColor = WireframeColor(0.36f, 0.92f, 1f, 0.38f),
            traceColor = WireframeColor(0.86f, 0.56f, 1f, 0.34f),
        )
        NowPlayingVisualizerPreset.CrystalPeaks3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Crystal,
            xScale = 5.7f,
            yScale = 2.75f,
            zScale = 2.15f,
            edgeTrim = 0.08f,
            horizontalStride = 2,
            bloomStrength = 0.10f,
            centerColor = WireframeColor(0.82f, 0.9f, 1f, 0.46f),
            peakColor = WireframeColor(0.78f, 0.92f, 1f, 0.48f),
            upperColor = WireframeColor(1f, 1f, 1f, 0.38f),
        )
        NowPlayingVisualizerPreset.PrismFan3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Fan,
            xScale = 5.2f,
            yScale = 2.2f,
            zScale = 2.4f,
            edgeTrim = 0.055f,
            diagonalStride = 2,
            bloomStrength = 0.14f,
            peakColor = WireframeColor(1f, 0.86f, 0.28f, 0.44f),
            upperColor = WireframeColor(1f, 0.48f, 0.56f, 0.36f),
            traceColor = WireframeColor(0.45f, 0.68f, 1f, 0.34f),
            cameraY = 1.5f,
            cameraZ = 6.8f,
        )
        NowPlayingVisualizerPreset.WaveRibbon3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Ribbon,
            xScale = 6.0f,
            yScale = 1.9f,
            zScale = 2.3f,
            edgeTrim = 0.07f,
            bloomStrength = 0.12f,
            peakColor = WireframeColor(0.45f, 0.72f, 1f, 0.42f),
            upperColor = WireframeColor(0.36f, 1f, 0.78f, 0.38f),
            traceColor = WireframeColor(0.72f, 0.58f, 1f, 0.32f),
        )
        NowPlayingVisualizerPreset.KaleidoscopeWeb3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Kaleidoscope,
            xScale = 4.8f,
            yScale = 1.9f,
            zScale = 2.4f,
            edgeTrim = 0.015f,
            horizontalStride = 2,
            bloomStrength = 0.12f,
            peakColor = WireframeColor(0.22f, 1f, 0.22f, 0.44f),
            upperColor = WireframeColor(1f, 0.82f, 0.24f, 0.36f),
            lowerColor = WireframeColor(1f, 0.45f, 0.55f, 0.34f),
            cameraY = 1.9f,
            cameraZ = 6.9f,
        )
        NowPlayingVisualizerPreset.StarfieldWeb3D -> WireframeVisualizerStyle(
            geometry = WireframeGeometry.Starfield,
            xScale = 6.1f,
            yScale = 2.2f,
            zScale = 3.2f,
            edgeTrim = 0.09f,
            horizontalStride = 2,
            diagonalStride = 2,
            bloomStrength = 0.09f,
            centerColor = WireframeColor(1f, 1f, 1f, 0.5f),
            peakColor = WireframeColor(1f, 1f, 1f, 0.46f),
            upperColor = WireframeColor(0.56f, 0.68f, 1f, 0.34f),
            traceColor = WireframeColor(0.42f, 0.94f, 1f, 0.3f),
        )
        else -> WireframeVisualizerStyle()
    }

private data class WireframeVisualizerStyle(
    val geometry: WireframeGeometry = WireframeGeometry.Sheet,
    val xScale: Float = 6.4f,
    val yScale: Float = 2.35f,
    val recencyYScale: Float = 0.72f,
    val zScale: Float = 3.0f,
    val edgeTrim: Float = 0.075f,
    val centerStride: Int = 1,
    val horizontalStride: Int = 1,
    val diagonalStride: Int = 1,
    val accentStride: Int = 3,
    val peakRange: ClosedFloatingPointRange<Float> = 0.30f..0.78f,
    val bloomStrength: Float = 0.11f,
    val brightnessBase: Float = 0.82f,
    val brightnessReactive: Float = 1.35f,
    val brightnessIdle: Float = 0.42f,
    val centerIntensity: Float = 0.98f,
    val peakIntensity: Float = 0.78f,
    val upperIntensity: Float = 0.52f,
    val traceIntensity: Float = 0.45f,
    val lowerIntensity: Float = 0.54f,
    val centerColor: WireframeColor = WireframeColor(0.70f, 0.55f, 1.0f, 0.48f),
    val peakColor: WireframeColor = WireframeColor(0.12f, 1.0f, 0.22f, 0.42f),
    val upperColor: WireframeColor = WireframeColor(0.22f, 0.95f, 0.84f, 0.34f),
    val traceColor: WireframeColor = WireframeColor(0.28f, 0.55f, 1.0f, 0.32f),
    val lowerColor: WireframeColor = WireframeColor(1.0f, 0.46f, 0.62f, 0.36f),
    val cameraX: Float = 0f,
    val cameraY: Float = 1.25f,
    val cameraZ: Float = 6.2f,
    val fovDegrees: Double = 42.0,
)

private enum class WireframeGeometry {
    Sheet,
    Canyon,
    Tunnel,
    Halo,
    Spiral,
    Aurora,
    Crystal,
    Fan,
    Ribbon,
    Kaleidoscope,
    Starfield,
}

private data class WorldPoint(val x: Float, val y: Float, val z: Float)

private fun deterministicJitter(x: Float, z: Float): WorldPoint {
    val seed = sinF(x * 71.7f + z * 43.3f) * 43758.545f
    val a = (seed - seed.toInt()) * 2f - 1f
    val bSeed = sinF(x * 19.3f + z * 97.1f) * 24634.635f
    val b = (bSeed - bSeed.toInt()) * 2f - 1f
    val cSeed = sinF(x * 131.9f + z * 29.6f) * 31821.17f
    val c = (cSeed - cSeed.toInt()) * 2f - 1f
    return WorldPoint(a, b, c)
}

private fun Float.sign(): Float = when {
    this > 0f -> 1f
    this < 0f -> -1f
    else -> 0f
}

private fun sinF(value: Float): Float = sin(value.toDouble()).toFloat()

private fun cosF(value: Float): Float = cos(value.toDouble()).toFloat()
