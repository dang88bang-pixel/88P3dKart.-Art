package com.example.agent.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * CT45P-Bridge-Schicht — Filament-3D-Renderer, Honeywell-Scanner und
 * Raycast-Auswahl in einem Modul.
 *
 * Die Klasse bündelt drei Fähigkeiten des Honeywell CT45P:
 *
 * 1. **Filament-3D-Bridge** ([Filament3dBridge]) — Google Filament
 *    (`com.google.android.filament`) als nativer 3D-Renderer für die
 *    Live-Karte/Taktikansicht. **Wichtig:** Filament ist **keine
 *    Build-Abhängigkeit** dieses Moduls — der Zugriff erfolgt über
 *    Reflexion, damit das Modul ohne zusätzliche Gradle-Dependency
 *    kompiliert und die App auch ohne Filament-APK-Bestandteil läuft
 *    (graceful degradation mit [FilamentAvailability]). Wird Filament
 *    später als Abhängigkeit ergänzt, funktioniert die Bridge unverändert.
 *
 * 2. **Honeywell-Scanner-Receiver** ([HoneywellScannerReceiver]) — bindet
 *    den integrierten Barcode-/2D-Scanner des CT45P über die Intent-API
 *    an (`com.honeywell.decode.intent.ACTION`, Legacy-Action
 *    `com.honeywell.scan.decode.ACTION`). Voraussetzung ist die
 *    `<queries>`-Erweiterung im AndroidManifest (Phase 5 Härtung) —
 *    ohne sie liefert `queryIntentActivities()` auf Android 11+ nichts.
 *
 * 3. **Raycaster** ([Raycaster], [RaycastPicker]) — Screen-to-World-Ray
 *    und Prioritäts-Picking gemäß docs/UI_UX_PLAN.md (§2.3 Pick/Raycast):
 *    **Tag/Token > RTI-Voxel > Heatmap-Zelle > Avatar > Mesh > Punktwolke** —
 *    die oberste Ebene gewinnt, bei Gleichstand die kleinste Entfernung.
 *
 * Architektur-Referenzen: docs/AURA.md §1 (eigener Renderer statt
 * Google-Maps), docs/UI_UX_PLAN.md §2.3, docs/DEVICE_INTERACTION.md
 * (Geräte-Layer mit Raycast-Auswahl), docs/CHECKLIST.md 21.5.
 */
class CT45pFilamentBridge(
    context: Context,
    private val surfaceView: SurfaceView,
) {

    companion object {
        private const val TAG = "CT45pBridge"
    }

    /** Filament-Renderer-Bridge (Reflexion, optional). */
    val renderer = Filament3dBridge(surfaceView)

    /** Honeywell-Scanner-Receiver (Intent-API). */
    val scanner = HoneywellScannerReceiver(onDecoded = { data, symbology ->
        Log.i(TAG, "Barcode: '$data' (Symbologie: ${symbology ?: "unbekannt"})")
        onBarcode?.invoke(data, symbology)
    })

    /** Kamera-Beschreibung für den Raycaster (wird von der UI gesetzt). */
    @Volatile
    var camera: ViewCamera = ViewCamera()
        set(value) {
            field = value
            renderer.setCamera(value)
        }

    /** Callback für dekodierte Barcodes (Daten, Symbologie). */
    var onBarcode: ((String, String?) -> Unit)? = null

    /** Callback für Pick-Ergebnisse (Treffer oder null). */
    var onPick: ((PickHit?) -> Unit)? = null

    private val appContext = context.applicationContext

    /** Registrierte Pick-Ziele (Tag/Token, Voxel, Marker, Meshes …). */
    private val pickTargets = ArrayList<PickTarget>()

    private val picker = RaycastPicker()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false

    // ─── Lebenszyklus ──────────────────────────────────────────────────

    /** Startet Renderer (falls Filament verfügbar) und Scanner-Receiver. */
    fun start() {
        if (started) return
        started = true
        renderer.start()
        if (scanner.isAvailable(appContext)) {
            scanner.register(appContext)
        } else {
            Log.w(TAG, "Honeywell-Scanner nicht erreichbar — prüfe <queries> im Manifest")
        }
        Log.i(TAG, "CT45pBridge gestartet (Filament: ${FilamentAvailability.isAvailable})")
    }

    /** Stoppt Scanner-Receiver und Renderer. */
    fun stop() {
        if (!started) return
        started = false
        scanner.unregister(appContext)
        renderer.stop()
    }

    // ─── Pick-Ziele / Raycast ──────────────────────────────────────────

    /** Setzt die klickbaren Ziele (ersetzt bestehende Liste). */
    fun setPickTargets(targets: List<PickTarget>) {
        pickTargets.clear()
        pickTargets.addAll(targets)
    }

    /**
     * Führt einen Raycast bei Bildschirmkoordinaten aus (UI-Thread-sicher).
     * @return oberster Treffer gemäß Priorität (Tag/Token > RTI-Voxel >
     *         Heatmap-Zelle > Avatar > Mesh > Punktwolke), sonst null.
     */
    fun pick(screenX: Float, screenY: Float): PickHit? {
        if (!started) return null
        val ray = raycaster.screenToRay(screenX, screenY, surfaceView.width, surfaceView.height)
        val hit = picker.pick(ray, pickTargets)
        mainHandler.post { onPick?.invoke(hit) }
        return hit
    }

    /** Raycaster mit der aktuellen Kamera. */
    val raycaster: Raycaster
        get() = Raycaster(camera)
}

// ════════════════════════════════════════════════════════════════════════
// 1. Raycaster — reine Kotlin-Mathematik (keine externen Abhängigkeiten)
// ════════════════════════════════════════════════════════════════════════

/** 3D-Vektor (immutable) für Raycast-Mathematik. */
data class Float3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(o: Float3) = Float3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Float3) = Float3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Float3(x * s, y * s, z * s)

    fun dot(o: Float3): Float = x * o.x + y * o.y + z * o.z
    fun cross(o: Float3): Float3 = Float3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun norm(): Float = sqrt(x * x + y * y + z * z)

    /** Normalisiert (Nullvektor → Nullvektor). */
    fun normalized(): Float3 {
        val n = norm()
        if (n < 1e-9f) return this
        return Float3(x / n, y / n, z / n)
    }

    /** Distanz zum Punkt [o]. */
    fun distanceTo(o: Float3): Float = (this - o).norm()

    companion object {
        val ZERO = Float3(0f, 0f, 0f)
    }
}

/** Strahl: Ursprung + normalisierte Richtung. */
data class Ray(val origin: Float3, val direction: Float3)

/**
 * Kamerabeschreibung für den Raycaster — unabhängig vom Renderer.
 *
 * Die UI (bzw. die Filament-Bridge) überträgt hierher die aktuelle
 * Sicht: Position, Blickrichtung, Up-Vektor, vertikales Sichtfeld und
 * Seitenverhältnis. Daraus wird der Screen-to-World-Ray gebaut.
 */
data class ViewCamera(
    val position: Float3 = Float3(0f, 0f, 10f),
    val forward: Float3 = Float3(0f, 0f, -1f),
    val up: Float3 = Float3(0f, 1f, 0f),
    /** Vertikales Sichtfeld in Grad (Filament-Default ≈ 45°). */
    val verticalFovDeg: Float = 45f,
    val aspect: Float = 1.0f,
) {
    /** Recht-Hand-Basis (normalisiert). */
    val right: Float3 by lazy { forward.cross(up).normalized() }
    val upNormalized: Float3 by lazy { right.cross(forward).normalized() }

    init {
        require(verticalFovDeg in 1f..179f) { "Sichtfeld außerhalb des gültigen Bereichs" }
        require(aspect > 0f) { "Seitenverhältnis muss positiv sein" }
    }
}

/** Schnittpunkt-Test-Ergebnis. */
data class RayHit(val distance: Float, val point: Float3, val normal: Float3? = null)

/**
 * Reine Kotlin-Raycast-Mathematik (docs/UI_UX_PLAN.md §2.3).
 *
 * Wandelt Bildschirmkoordinaten in einen Weltstrahl um und testet
 * Kugeln (Marker/Avatare), achsenparallele Boxen (Voxel/Heatmap-Zellen)
 * und Dreiecke (Meshes, Möller–Trumbore).
 */
class Raycaster(private val camera: ViewCamera) {

    /**
     * Bildschirmkoordinate → Weltstrahl.
     *
     * Rechnung: NDC = ((2·x/w) − 1, 1 − (2·y/h)) — y invertiert, weil der
     * Bildschirmursprung oben links liegt. Der Strahl verläuft durch den
     * Punkt `position + forward + ndcX·right·tan(fov/2)·aspect + ndcY·up·tan(fov/2)`.
     */
    fun screenToRay(screenX: Float, screenY: Float, viewportW: Int, viewportH: Int): Ray {
        val w = max(viewportW, 1).toFloat()
        val h = max(viewportH, 1).toFloat()
        val ndcX = (2f * screenX / w) - 1f
        val ndcY = 1f - (2f * screenY / h)

        val tanHalf = tan(Math.toRadians(camera.verticalFovDeg / 2.0).toFloat())
        val dir = camera.forward.normalized()
            .plus(camera.right * (ndcX * tanHalf * camera.aspect))
            .plus(camera.upNormalized * (ndcY * tanHalf))
            .normalized()
        return Ray(camera.position, dir)
    }

    /** Strahl-Kugel-Schnitt (Marker, Avatare, Tag/Token). */
    fun intersectSphere(ray: Ray, center: Float3, radius: Float): RayHit? {
        val oc = ray.origin - center
        val b = oc.dot(ray.direction)
        val c = oc.dot(oc) - radius * radius
        val disc = b * b - c
        if (disc < 0f) return null
        val t = -b - sqrt(disc)
        if (t < 0f) return null
        val point = ray.origin + ray.direction * t
        val normal = (point - center).normalized()
        return RayHit(t, point, normal)
    }

    /** Strahl-AABB-Schnitt (RTI-Voxel, Heatmap-Zellen) — slabs-Methode. */
    fun intersectAabb(ray: Ray, min: Float3, max: Float3): RayHit? {
        var tMin = 0f
        var tMax = Float.MAX_VALUE
        for (axis in 0..2) {
            val o = if (axis == 0) ray.origin.x else if (axis == 1) ray.origin.y else ray.origin.z
            val d = if (axis == 0) ray.direction.x else if (axis == 1) ray.direction.y else ray.direction.z
            val lo = if (axis == 0) min.x else if (axis == 1) min.y else min.z
            val hi = if (axis == 0) max.x else if (axis == 1) max.y else max.z
            if (abs(d) < 1e-9f) {
                if (o < lo || o > hi) return null // parallel außerhalb
                continue
            }
            var t1 = (lo - o) / d
            var t2 = (hi - o) / d
            if (t1 > t2) {
                val tmp = t1; t1 = t2; t2 = tmp
            }
            tMin = max(tMin, t1)
            tMax = min(tMax, t2)
            if (tMin > tMax) return null
        }
        if (tMax < 0f) return null
        val t = if (tMin > 0f) tMin else tMax
        return RayHit(t, ray.origin + ray.direction * t)
    }

    /** Strahl-Dreieck-Schnitt (Möller–Trumbore) — für Mesh-Oberflächen. */
    fun intersectTriangle(ray: Ray, a: Float3, b: Float3, c: Float3): RayHit? {
        val e1 = b - a
        val e2 = c - a
        val p = ray.direction.cross(e2)
        val det = e1.dot(p)
        if (abs(det) < 1e-9f) return null
        val invDet = 1f / det
        val tVec = ray.origin - a
        val u = tVec.dot(p) * invDet
        if (u < 0f || u > 1f) return null
        val q = tVec.cross(e1)
        val v = ray.direction.dot(q) * invDet
        if (v < 0f || u + v > 1f) return null
        val t = e2.dot(q) * invDet
        if (t < 0f) return null
        return RayHit(t, ray.origin + ray.direction * t, e1.cross(e2).normalized())
    }
}

// ─── Prioritäts-Picking (docs/UI_UX_PLAN.md §2.3) ───────────────────────

/** Prioritätsstufen — die oberste Ebene gewinnt beim Picking. */
enum class PickPriority(val level: Int) {
    TAG_TOKEN(0),      // Tag/Token — oberste Ebene
    RTI_VOXEL(1),      // RTI-Voxel (RF-Tomographie)
    HEATMAP_CELL(2),   // Heatmap-Zelle
    AVATAR(3),         // Avatar/Person
    MESH(4),           // Mesh (Wände/Boden)
    POINT_CLOUD(5),    // Punktwolke — unterste Ebene
}

/** Klickbares Ziel im 3D-Raum (Marker, Voxel, Zelle, Avatar, Mesh). */
interface PickTarget {
    val id: String
    val priority: PickPriority

    /**
     * Schnittpunkttest gegen [ray].
     * @return Entfernung entlang des Strahls oder null bei keinem Treffer.
     */
    fun hitTest(ray: Ray): Float?
}

/** Einzelner Treffer beim Raycast. */
data class PickHit(
    val target: PickTarget,
    val distance: Float,
)

/**
 * Prioritäts-Picker: wählt aus allen Treffern den mit der **obersten
 * Ebene** (kleinster [PickPriority.level]); bei Gleichstand gewinnt die
 * kleinste Entfernung. Entspricht der Raycast-Priorität aus
 * docs/UI_UX_PLAN.md: Tag/Token > RTI-Voxel > Heatmap-Zelle > Avatar >
 * Mesh > Punktwolke.
 */
class RaycastPicker(private val maxDistance: Float = 100f) {

    fun pick(ray: Ray, targets: List<PickTarget>): PickHit? {
        var best: PickHit? = null
        for (target in targets) {
            val d = target.hitTest(ray) ?: continue
            if (d < 0f || d > maxDistance) continue
            val current = best
            if (current == null ||
                target.priority.level < current.target.priority.level ||
                (target.priority.level == current.target.priority.level && d < current.distance)
            ) {
                best = PickHit(target, d)
            }
        }
        return best
    }
}

// ════════════════════════════════════════════════════════════════════════
// 2. Filament-3D-Bridge (Reflexion — kompiliert ohne Filament-Dependency)
// ════════════════════════════════════════════════════════════════════════

/**
 * Verfügbarkeitsprüfung für Google Filament zur Laufzeit.
 *
 * Filament ist bewusst **keine** Gradle-Abhängigkeit dieses Moduls
 * (die App kompiliert und läuft auch ohne); sobald die Bibliothek im
 * APK steckt (`implementation("com.google.android.filament:filament-android")`),
 * erkennt [isAvailable] sie und die Bridge schaltet auf den echten
 * Renderer um. Alle Aufrufe laufen über Reflexion, damit der Bytecode
 * des Moduls ohne Filament-Klassen auskommt.
 */
object FilamentAvailability {

    private const val ENGINE_CLASS = "com.google.android.filament.Engine"

    /** true, wenn Filament zur Laufzeit im Classpath liegt. */
    val isAvailable: Boolean by lazy {
        runCatching {
            Class.forName(ENGINE_CLASS)
            Log.i("FilamentAvailability", "Google Filament erkannt — 3D-Beschleunigung aktiv")
            true
        }.getOrDefault(false)
    }

    /** Filament-Version (z. B. "1.51.4") oder null. */
    val version: String? by lazy {
        if (!isAvailable) return@lazy null
        runCatching {
            val engine = Class.forName(ENGINE_CLASS)
            val m = engine.getMethod("getVersion")
            m.invoke(null)?.toString()
        }.getOrNull()
    }
}

/**
 * Reflexions-Bridge auf Google Filament ([SurfaceView] als Rendering-
 * Ziel). Verwaltet Engine/Scene/View/Camera/Viewport/Swapchain und den
 * Frame-Render-Loop über die öffentliche Filament-API — aufgerufen per
 * `Class.forName`, daher ohne Compile-Zeit-Abhängigkeit.
 *
 * Lebenszyklus:
 * - [start] erzeugt Engine, Scene, View, Camera und hängt den
 *   SurfaceHolder-Callback an,
 * - [renderFrame] rendert einen Frame (via `Engine.renderer`),
 * - [stop] zerstört alle Ressourcen in Filament-Reihenfolge.
 *
 * Fallback: Ist Filament nicht verfügbar, loggt die Bridge einmalig
 * einen Hinweis und bleibt inaktiv — die App funktioniert mit ihrem
 * 2D-Overlay weiter (graceful degradation).
 */
class Filament3dBridge(private val surfaceView: SurfaceView) {

    companion object {
        private const val TAG = "FilamentBridge"
        private const val ENGINE = "com.google.android.filament.Engine"
        private const val SCENE = "com.google.android.filament.Scene"
        private const val VIEW = "com.google.android.filament.View"
        private const val CAMERA = "com.google.android.filament.Camera"
        private const val RENDERER = "com.google.android.filament.Renderer"
        private const val VIEWPORT = "com.google.android.filament.Viewport"
    }

    @Volatile
    private var running = false

    // Reflexions-Handles (null solange Filament fehlt).
    private var engine: Any? = null
    private var scene: Any? = null
    private var view: Any? = null
    private var camera: Any? = null
    private var renderer: Any? = null
    private var swapChain: Any? = null
    private var cameraEntity: Int = 0

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = attachSurface(holder)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            configureViewport(w, h)
            renderFrame()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) = detachSurface()
    }

    val isRunning: Boolean get() = running

    /** Startet die Bridge; no-op ohne Filament im Classpath. */
    fun start() {
        if (running) return
        if (!FilamentAvailability.isAvailable) {
            Log.w(TAG, "Filament nicht verfügbar — 3D-Bridge inaktiv (2D-Overlay aktiv)")
            return
        }
        try {
            engine = createEngine()
            if (engine == null) {
                Log.e(TAG, "Engine.create lieferte null — Bridge bleibt inaktiv")
                return
            }
            scene = engine?.invoke("createScene")
            view = engine?.invoke("createView")
            renderer = engine?.invoke("createRenderer")
            cameraEntity = (engine?.invoke("createCameraEntity") as? Int) ?: 0
            camera = engine?.let { it.invoke("getCamera", cameraEntity) }
            view?.invoke("setScene", scene)
            view?.invoke("setCamera", camera)
            configureViewport(surfaceView.width, surfaceView.height)
            surfaceView.holder.addCallback(holderCallback)
            running = true
            Log.i(TAG, "Filament-Bridge aktiv (Version: ${FilamentAvailability.version})")
        } catch (e: Exception) {
            Log.e(TAG, "Filament-Start fehlgeschlagen: ${e.message}")
            running = false
        }
    }

    /** Stoppt die Bridge und gibt Filament-Ressourcen frei. */
    fun stop() {
        if (!running) return
        running = false
        runCatching { surfaceView.holder.removeCallback(holderCallback) }
        detachSurface()
        runCatching {
            engine?.invoke("destroyCameraEntity", cameraEntity)
            view?.invoke("destroy")
            scene?.invoke("destroy")
            renderer?.invoke("destroy")
            engine?.invoke("destroy")
        }
        engine = null; scene = null; view = null; camera = null
        renderer = null; swapChain = null; cameraEntity = 0
        Log.i(TAG, "Filament-Bridge gestoppt")
    }

    /** Überträgt die Kamera-Beschreibung an Filament (Position, Blick, FOV). */
    fun setCamera(vc: ViewCamera) {
        val cam = camera ?: return
        runCatching {
            val eye = doubleArrayOf(vc.position.x.toDouble(), vc.position.y.toDouble(), vc.position.z.toDouble())
            val center = doubleArrayOf(
                (vc.position.x + vc.forward.x).toDouble(),
                (vc.position.y + vc.forward.y).toDouble(),
                (vc.position.z + vc.forward.z).toDouble(),
            )
            val up = doubleArrayOf(vc.up.x.toDouble(), vc.up.y.toDouble(), vc.up.z.toDouble())
            cam.invoke("lookAt", eye, center, up)
            cam.invoke("setProjection", verticalFovEnum(), vc.verticalFovDeg.toDouble(), vc.aspect.toDouble(), 0.05, 1000.0)
        }.onFailure { Log.w(TAG, "setCamera fehlgeschlagen: ${it.message}") }
    }

    /** Rendert einen Frame (Engine.renderer → render(swapChain, view)). */
    fun renderFrame() {
        if (!running) return
        val r = renderer ?: return
        val v = view ?: return
        val sc = swapChain ?: return
        runCatching { r.invoke("render", sc, v) }
            .onFailure { Log.w(TAG, "renderFrame fehlgeschlagen: ${it.message}") }
    }

    // ─── Intern ────────────────────────────────────────────────────────

    private fun createEngine(): Any? =
        runCatching { Class.forName(ENGINE).getMethod("create").invoke(null) }
            .getOrElse { e ->
                Log.e(TAG, "Engine.create fehlgeschlagen: ${e.message}")
                null
            }

    /** Filament-Enum `Camera.Projection.VERTICAL_FOV` per Reflexion. */
    private fun verticalFovEnum(): Any? =
        runCatching {
            val clazz = Class.forName("$CAMERA\$Projection")
            clazz.getMethod("valueOf", String::class.java).invoke(null, "VERTICAL_FOV")
        }.getOrElse { e ->
            Log.w(TAG, "Camera.Projection nicht auflösbar: ${e.message}")
            null
        }

    /** Erzeugt ein `Viewport(0, 0, w, h)` per Reflexion. */
    private fun viewport(w: Int, h: Int): Any? =
        runCatching {
            val clazz = Class.forName(VIEWPORT)
            clazz.constructors.firstOrNull { it.parameterCount == 4 }
                ?.newInstance(0, 0, w, h)
        }.getOrElse { e ->
            Log.w(TAG, "Viewport nicht erzeugbar: ${e.message}")
            null
        }

    private fun attachSurface(holder: SurfaceHolder) {
        if (!running) return
        runCatching {
            swapChain = engine?.invoke("createSwapChain", holder.surface)
            view?.invoke("setViewport", viewport(surfaceView.width, surfaceView.height))
            renderFrame()
        }.onFailure { Log.e(TAG, "attachSurface fehlgeschlagen: ${it.message}") }
    }

    private fun detachSurface() {
        val sc = swapChain ?: return
        runCatching { engine?.invoke("destroySwapChain", sc) }
        swapChain = null
    }

    private fun configureViewport(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        runCatching {
            view?.invoke("setViewport", viewport(w, h))
            camera?.invoke("setProjection", verticalFovEnum(), 45.0, w.toDouble() / h, 0.05, 1000.0)
        }.onFailure { Log.w(TAG, "configureViewport fehlgeschlagen: ${it.message}") }
    }

    /**
     * Reflexions-Helfer: ruft [method] auf [receiver] mit [args] auf.
     *
     * Die Methode wird über **Name + Parameteranzahl** gesucht (nicht über
     * exakte Class-Typen), damit gepingte Primitive (z. B. `Int` für
     * `getCamera(int)`) und null-Argumente zuverlässig auflösen —
     * `Method.invoke` entpackt Boxing automatisch.
     */
    private fun Any.invoke(method: String, vararg args: Any?): Any? {
        val clazz = if (this is Class<*>) this else this.javaClass
        val m = clazz.methods.firstOrNull { it.name == method && it.parameterCount == args.size }
            ?: throw NoSuchMethodException("$clazz.$method(${args.size} Parameter)")
        return m.invoke(if (this is Class<*>) null else this, *args)
    }
}

// ════════════════════════════════════════════════════════════════════════
// 3. Honeywell-Scanner-Receiver (Intent-API, keine SDK-Abhängigkeit)
// ════════════════════════════════════════════════════════════════════════

/**
 * Empfänger für die Barcode-Dekodierung des Honeywell CT45P über die
 * Intent-API — ohne Honeywell-SDK-Abhängigkeit (die AIDC-Bibliothek
 * `com.honeywell.aidc` wird per Reflexion nur auf Verfügbarkeit geprüft).
 *
 * Unterstützte Schnittstellen:
 * - **DataCollection-Intent** (`com.honeywell.decode.intent.ACTION`):
 *   Extra `decoded_data` bzw. `com.honeywell.decode.intent.extra.STRING_DATA`,
 * - **Legacy-Intent** (`com.honeywell.scan.decode.ACTION`): Extra
 *   `data` bzw. `com.honeywell.scan.decode.extra.DATA`,
 * - **AIDC-SDK** (`com.honeywell.aidc.AidcManager`): wird per Reflexion
 *   erkannt; die vollständige AIDC-Anbindung (Reader-API) benötigt das
 *   SDK als Dependency und ist als Roadmap-Thema dokumentiert
 *   (docs/TRIANGULATION.md §Roadmap, Honeywell Mobility SDK).
 *
 * **Phase 5 Härtung:** Ohne die `<queries>`-Erweiterung im Manifest
 * (Pakete `com.honeywell.decode`, `com.honeywell.aidc` + Intent-Actions)
 * liefert [isAvailable] auf Android 11+ immer false — Package-Visibility
 * blockiert `queryIntentActivities()`.
 */
class HoneywellScannerReceiver(
    private val onDecoded: (data: String, symbology: String?) -> Unit,
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "HwScanner"

        /** DataCollection-Intent-Action (Honeywell Mobility SDK). */
        const val ACTION_DECODE = "com.honeywell.decode.intent.ACTION"

        /** Legacy-Scan-Intent-Action. */
        const val ACTION_LEGACY = "com.honeywell.scan.decode.ACTION"

        // Übliche Extras der Honeywell-Intent-APIs.
        private const val EXTRA_DATA_1 = "decoded_data"
        private const val EXTRA_DATA_2 = "com.honeywell.decode.intent.extra.STRING_DATA"
        private const val EXTRA_DATA_3 = "data"
        private const val EXTRA_DATA_4 = "com.honeywell.scan.decode.extra.DATA"
        private const val EXTRA_SYMBOLOGY = "symbology"

        /** AIDC-SDK-Klassen (Verfügbarkeitsprüfung per Reflexion). */
        private const val AIDC_MANAGER_CLASS = "com.honeywell.aidc.AidcManager"
        private const val AIDC_READER_CLASS = "com.honeywell.aidc.BarcodeReader"

        private var registered = false

        /**
         * true, wenn ein Honeywell-Scanner-Dienst im System erreichbar ist.
         * Erfordert die `<queries>`-Erweiterung im Manifest (Phase 5).
         */
        fun isAvailable(context: Context): Boolean {
            val pm = context.packageManager
            val decode = runCatching {
                pm.queryIntentActivities(
                    Intent(ACTION_DECODE).addCategory(Intent.CATEGORY_DEFAULT),
                    0,
                )
            }.getOrDefault(emptyList())
            if (decode.isNotEmpty()) return true

            val legacy = runCatching {
                pm.queryIntentActivities(
                    Intent(ACTION_LEGACY).addCategory(Intent.CATEGORY_DEFAULT),
                    0,
                )
            }.getOrDefault(emptyList())
            if (legacy.isNotEmpty()) return true

            // AIDC-SDK direkt im Classpath (Reader ist dann über die
            // BarcodeReader-API erreichbar, auch ohne Intent-Registrierung).
            return runCatching {
                Class.forName(AIDC_MANAGER_CLASS)
                Class.forName(AIDC_READER_CLASS)
                true
            }.getOrDefault(false)
        }

        /** Liefert die erkannte AIDC-Version (Reflexion) oder null. */
        fun aidcVersion(): String? =
            runCatching {
                Class.forName(AIDC_MANAGER_CLASS)
                    .getMethod("getVersion")
                    .invoke(null)
                    ?.toString()
            }.getOrNull()
    }

    /** Registriert den Receiver (idempotent; ab API 33 nicht-exportiert). */
    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_DECODE)
            addAction(ACTION_LEGACY)
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        ContextCompat.registerReceiver(context, this, filter, flags)
        registered = true
        Log.i(TAG, "Honeywell-Scanner-Receiver registriert (AIDC: ${aidcVersion() ?: "nicht im APK"})")
    }

    /** Entfernt den Receiver (idempotent). */
    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
        registered = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            ACTION_DECODE, ACTION_LEGACY -> {
                val data = extractString(intent)
                if (data != null) {
                    val symbology = intent.getStringExtra(EXTRA_SYMBOLOGY)
                    Log.d(TAG, "Scan empfangen (${intent.action}): ${data.take(40)}")
                    onDecoded(data, symbology)
                } else {
                    Log.w(TAG, "Scan-Intent ohne Daten-Extra empfangen")
                }
            }
        }
    }

    private fun extractString(intent: Intent): String? {
        for (key in listOf(EXTRA_DATA_1, EXTRA_DATA_2, EXTRA_DATA_3, EXTRA_DATA_4)) {
            intent.getStringExtra(key)?.let { return it }
        }
        // Fallback: Byte-Array-Extras (manche Honeywell-Firmware-Versionen).
        for (key in listOf(EXTRA_DATA_1, EXTRA_DATA_3)) {
            (intent.getByteArrayExtra(key)?.toString(Charsets.UTF_8))?.let { return it }
        }
        return null
    }
}
