package com.example.agent.pipeline

import android.content.Context
import android.util.Log

/**
 * Orchestriert die 6 Stufen der v2.0-Datenpipeline.
 *
 *   Sensor-/Netzwerkdaten → Analyse → Mesh → 3D-Umgebung → Exakte Abbildung → Evaluierungsagent
 */
class PipelineOrchestrator(context: Context) {

    companion object {
        private const val TAG = "Pipeline"
    }

    data class PipelineResult(
        val status: String,
        val numPoints: Int,
        val numMeshVertices: Int,
        val numMeshFaces: Int,
        val numObjects: Int,
        val objectKinds: List<String>,
        val confidence: Float,
        val transform: ExactMapper.Transform3D,
        val evaluation: EvaluationAgent.Evaluation,
    )

    // Bugfix: Vorher wurde hier `android.app.Application()` als
    // Pseudo-Context erzeugt — das ist ein Anti-Pattern (leeres
    // Application-Objekt ohne echten Lebenszyklus) und konnte bei jedem
    // späteren Zugriff auf System-Services crashen. Jetzt wird der
    // echte Context aus MainActivity weitergereicht.
    private val acquisition = DataAcquisitionService(context.applicationContext)
    private val interpreter = DataInterpreter()
    private val meshGenerator = MeshGenerator()
    private val reconstructor = EnvironmentReconstructor()
    private val mapper = ExactMapper()
    private val evaluator = EvaluationAgent()

    fun run(flatPoints: List<Float>, source: String = "lidar"): PipelineResult {
        // 1. Erfassung
        acquisition.ingest(flatPoints, source)
        val points = acquisition.snapshot()

        // 2. Interpretation
        val objects = interpreter.interpret(points)

        // 3. Mesh
        val mesh = meshGenerator.generate(points)

        // 4. Umgebung
        val environment = reconstructor.reconstruct(points, objects)

        // 5. Exakte Abbildung
        val mapping = mapper.map(points)

        // 6. Evaluation
        val evaluation = evaluator.evaluate(points, mesh, environment, mapping)
            .copy(numObjects = objects.size)

        val result = PipelineResult(
            status = evaluation.status,
            numPoints = evaluation.numPoints,
            numMeshVertices = mesh.vertices.size,
            numMeshFaces = mesh.faces.size,
            numObjects = objects.size,
            objectKinds = objects.map { it.kind },
            confidence = evaluation.confidence,
            transform = mapper.toTransform3D(mapping),
            evaluation = evaluation,
        )

        Log.d(TAG, "Pipeline abgeschlossen: ${result.numPoints} Punkte, " +
            "confidence=${result.confidence}")
        return result
    }
}
