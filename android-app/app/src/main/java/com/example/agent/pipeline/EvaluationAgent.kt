package com.example.agent.pipeline

import kotlin.math.abs

/**
 * Stufe 6 — Evaluierungsagent / Service Worker.
 *
 * Bewertet die Qualität der rekonstruierten Umgebung (Abdeckung, Dichte,
 * Mapping-Residuum) und erzeugt einen Konfidenzwert.
 */
class EvaluationAgent {

    data class Evaluation(
        val numPoints: Int,
        val numFaces: Int,
        val numObjects: Int,
        val coverage: Float,
        val densityPtsPerM2: Float,
        val volumeM3: Float,
        val floorAreaM2: Float,
        val mappingResidual: Float,
        val confidence: Float,
        val status: String,
    )

    fun evaluate(
        points: List<DataAcquisitionService.SensorDataPoint>,
        mesh: MeshGenerator.Mesh,
        environment: EnvironmentReconstructor.Environment,
        mapping: ExactMapper.ExactMapping,
    ): Evaluation {
        val n = points.size
        val coverage = if (n > 0) 1f else 0f
        val density = if (environment.floorArea > 0f) n / environment.floorArea else 0f

        val residualScore = 1f / (1f + abs(mapping.residual))
        val meshScore = if (mesh.faces.isNotEmpty()) 1f else 0.5f
        val confidence = (0.4f * coverage + 0.3f * residualScore + 0.3f * meshScore)
            .coerceIn(0f, 1f)

        return Evaluation(
            numPoints = n,
            numFaces = mesh.faces.size,
            numObjects = 0, // wird vom Orchestrator gesetzt
            coverage = coverage,
            densityPtsPerM2 = density,
            volumeM3 = environment.volume,
            floorAreaM2 = environment.floorArea,
            mappingResidual = mapping.residual,
            confidence = confidence,
            status = if (n > 0) "ready" else "empty",
        )
    }
}
