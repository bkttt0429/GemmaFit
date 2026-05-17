package com.gemmafit.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionReportParserTest {
    @Test
    fun missingEvidenceDagAndCapabilityContractStayBackwardCompatible() {
        val report = MotionReportParser.parse(
            JSONObject(
                """
                {
                  "exercise": "squat",
                  "exercise_confidence": 0.91,
                  "exercise_basis": ["knee_hip_pattern"],
                  "template_metrics": {"squat_depth": 0.82},
                  "quality_flags": [
                    {
                      "id": "gate.squat_depth",
                      "evidence_id": "gate.squat_depth.0",
                      "status": "OK",
                      "value": 0.82,
                      "threshold": 0.60,
                      "evidence": "depth_visible"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        assertEquals("squat", report.exercise)
        assertEquals(0.91f, report.confidence, 0.001f)
        assertEquals(listOf("knee_hip_pattern"), report.basis)
        assertEquals(0.82f, report.metrics.getValue("squat_depth"), 0.001f)
        assertEquals("gate.squat_depth.0", report.qualityFlags.single().evidenceId)
        assertTrue(report.capabilityContract.canJudge.isEmpty())
        assertTrue(report.capabilityContract.cannotJudge.isEmpty())
        assertTrue(report.evidenceDag.nodes.isEmpty())
        assertTrue(report.evidenceDag.edges.isEmpty())
    }

    @Test
    fun fullEvidenceDagAndCapabilityContractParseAllContractFields() {
        val report = MotionReportParser.parse(
            JSONObject(
                """
                {
                  "exercise": "squat",
                  "capability_contract": {
                    "can_judge": [
                      {
                        "metric": "squat_depth",
                        "reason": "side_view_depth_visible",
                        "confidence_ceiling": 0.90,
                        "required_evidence": ["hip_knee_visible"],
                        "evidence_refs": ["metric.squat.depth"]
                      }
                    ],
                    "cannot_judge": [
                      {
                        "metric": "frontal_knee_valgus",
                        "reason": "side_view",
                        "confidence_ceiling": 0.0,
                        "required_evidence": ["frontal_view"],
                        "evidence_refs": ["capability.frontal_knee_valgus.blocked"]
                      }
                    ]
                  },
                  "evidence_dag": {
                    "nodes": [
                      {
                        "id": "metric.squat.depth",
                        "type": "template_metric",
                        "label": "Squat depth",
                        "metric": "squat_depth",
                        "value": 0.82,
                        "unit": "ratio",
                        "confidence": 0.88,
                        "status": "OK",
                        "source_module": "motion_quality.cpp",
                        "source_function": "build_squat_contract",
                        "frame_range": "frame:12",
                        "evidence_level": "prototype_metric",
                        "reason": "pose_based_template_metric",
                        "landmark_refs": ["left_hip", "left_knee"]
                      },
                      {
                        "id": "capability.squat_depth.can_judge",
                        "type": "capability",
                        "label": "Can judge squat depth",
                        "metric": "squat_depth",
                        "value": 1.0,
                        "confidence": 0.90,
                        "status": "OK",
                        "source_module": "motion_quality.cpp",
                        "source_function": "build_capability_contract",
                        "frame_range": "session",
                        "evidence_level": "capability_contract",
                        "reason": "supported_by_current_session_evidence"
                      }
                    ],
                    "edges": [
                      {
                        "from": "metric.squat.depth",
                        "to": "capability.squat_depth.can_judge",
                        "relation": "supports"
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )

        val canJudge = report.capabilityContract.canJudge.single()
        val cannotJudge = report.capabilityContract.cannotJudge.single()
        val metricNode = report.evidenceDag.nodes.first { it.id == "metric.squat.depth" }
        val edge = report.evidenceDag.edges.single()

        assertEquals("squat_depth", canJudge.metric)
        assertEquals("side_view_depth_visible", canJudge.reason)
        assertEquals(0.90f, canJudge.confidenceCeiling, 0.001f)
        assertEquals(listOf("hip_knee_visible"), canJudge.requiredEvidence)
        assertEquals(listOf("metric.squat.depth"), canJudge.evidenceRefs)
        assertEquals("frontal_knee_valgus", cannotJudge.metric)
        assertEquals("side_view", cannotJudge.reason)
        assertEquals("prototype_metric", metricNode.evidenceLevel)
        assertEquals("pose_based_template_metric", metricNode.reason)
        assertEquals(listOf("left_hip", "left_knee"), metricNode.landmarkRefs)
        assertEquals("metric.squat.depth", edge.from)
        assertEquals("capability.squat_depth.can_judge", edge.to)
        assertEquals("supports", edge.relation)
    }
}
