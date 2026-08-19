package com.example.agent.classification

import com.example.agent.classification.SemanticClassifier.ClassifiedPoint
import com.example.agent.classification.SemanticClassifier.SemanticType

/**
 * Live-View vs. Persistierte Ansicht (Spezifikation §3.4):
 * Im LIVE-Modus werden alle Punkte gerendert; im PERSISTED-Modus werden
 * Personen/Tiere (Live-Only) vor der Darstellung entfernt — dieselbe Regel,
 * die PersistenceFilter vor dem Speichern erzwingt.
 */
class ViewController(private val semanticClassifier: SemanticClassifier = SemanticClassifier()) {

    enum class ViewMode { LIVE, PERSISTED }

    fun renderFrame(points: List<Point3D>, mode: ViewMode): List<ClassifiedPoint> {
        val classified = semanticClassifier.classify(points)
        return when (mode) {
            ViewMode.LIVE -> classified
            ViewMode.PERSISTED -> classified.filterNot { it.liveOnly }
        }
    }

    companion object {
        val PERSISTED_TYPES = setOf(
            SemanticType.PERSON,
            SemanticType.ANIMAL,
        )
    }
}
