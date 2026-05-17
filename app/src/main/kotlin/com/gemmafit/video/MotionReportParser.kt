package com.gemmafit.video

import org.json.JSONArray
import org.json.JSONObject

internal data class ParsedMotionReport(
    val exercise: String = "unknown",
    val confidence: Float = 0f,
    val basis: List<String> = emptyList(),
    val candidateScores: Map<String, Float> = emptyMap(),
    val metrics: Map<String, Float> = emptyMap(),
    val qualityFlags: List<QualityFlag> = emptyList(),
    val notApplicable: List<QualityFlag> = emptyList(),
    val capabilityContract: CapabilityContract = CapabilityContract(),
    val evidenceDag: EvidenceDag = EvidenceDag(),
)

internal object MotionReportParser {
    fun parse(root: JSONObject): ParsedMotionReport {
        return ParsedMotionReport(
            exercise = root.optString("exercise", "unknown"),
            confidence = root.optDouble("exercise_confidence", 0.0).toFloat(),
            basis = parseStringArray(root.optJSONArray("exercise_basis")),
            candidateScores = parseFloatMap(root.optJSONObject("candidate_scores")),
            metrics = parseFloatMap(root.optJSONObject("template_metrics")),
            qualityFlags = parseQualityFlags(root.optJSONArray("quality_flags")),
            notApplicable = parseQualityFlags(root.optJSONArray("not_applicable")),
            capabilityContract = parseCapabilityContract(root.optJSONObject("capability_contract")),
            evidenceDag = parseEvidenceDag(root.optJSONObject("evidence_dag")),
        )
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val values = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            values.add(arr.optString(i))
        }
        return values
    }

    private fun parseFloatMap(obj: JSONObject?): Map<String, Float> {
        if (obj == null) return emptyMap()
        val values = mutableMapOf<String, Float>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            values[key] = obj.optDouble(key, 0.0).toFloat()
        }
        return values
    }

    private fun parseQualityFlags(arr: JSONArray?): List<QualityFlag> {
        if (arr == null) return emptyList()
        val flags = mutableListOf<QualityFlag>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            flags.add(
                QualityFlag(
                    id = obj.optString("id", "quality_gate"),
                    evidenceId = obj.optString("evidence_id", ""),
                    status = obj.optString("status", "MONITOR"),
                    value = obj.optDouble("value", 0.0).toFloat(),
                    threshold = obj.optDouble("threshold", 0.0).toFloat(),
                    evidence = obj.optString("evidence", "prototype_threshold"),
                    reason = obj.optString("reason", ""),
                    rule = obj.optInt("rule", 0),
                    joint = obj.optString("joint", ""),
                )
            )
        }
        return flags
    }

    private fun parseCapabilityContract(obj: JSONObject?): CapabilityContract {
        if (obj == null) return CapabilityContract()
        return CapabilityContract(
            canJudge = parseCapabilityItems(obj.optJSONArray("can_judge")),
            cannotJudge = parseCapabilityItems(obj.optJSONArray("cannot_judge")),
        )
    }

    private fun parseCapabilityItems(arr: JSONArray?): List<CapabilityJudgment> {
        if (arr == null) return emptyList()
        val items = mutableListOf<CapabilityJudgment>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            items.add(
                CapabilityJudgment(
                    metric = obj.optString("metric", ""),
                    reason = obj.optString("reason", ""),
                    confidenceCeiling = obj.optDouble("confidence_ceiling", 0.0).toFloat(),
                    requiredEvidence = parseStringArray(obj.optJSONArray("required_evidence")),
                    evidenceRefs = parseStringArray(obj.optJSONArray("evidence_refs")),
                )
            )
        }
        return items.filter { it.metric.isNotBlank() }
    }

    private fun parseEvidenceDag(obj: JSONObject?): EvidenceDag {
        if (obj == null) return EvidenceDag()
        return EvidenceDag(
            nodes = parseEvidenceDagNodes(obj.optJSONArray("nodes")),
            edges = parseEvidenceDagEdges(obj.optJSONArray("edges")),
        )
    }

    private fun parseEvidenceDagNodes(arr: JSONArray?): List<EvidenceDagNode> {
        if (arr == null) return emptyList()
        val nodes = mutableListOf<EvidenceDagNode>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            nodes.add(
                EvidenceDagNode(
                    id = obj.optString("id", ""),
                    type = obj.optString("type", ""),
                    label = obj.optString("label", ""),
                    metric = obj.optString("metric", ""),
                    value = obj.optDouble("value", 0.0).toFloat(),
                    unit = obj.optString("unit", ""),
                    confidence = obj.optDouble("confidence", 0.0).toFloat(),
                    status = obj.optString("status", ""),
                    sourceModule = obj.optString("source_module", ""),
                    sourceFunction = obj.optString("source_function", ""),
                    frameRange = obj.optString("frame_range", ""),
                    evidenceLevel = obj.optString("evidence_level", ""),
                    reason = obj.optString("reason", ""),
                    landmarkRefs = parseStringArray(obj.optJSONArray("landmark_refs")),
                )
            )
        }
        return nodes.filter { it.id.isNotBlank() }
    }

    private fun parseEvidenceDagEdges(arr: JSONArray?): List<EvidenceDagEdge> {
        if (arr == null) return emptyList()
        val edges = mutableListOf<EvidenceDagEdge>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            edges.add(
                EvidenceDagEdge(
                    from = obj.optString("from", ""),
                    to = obj.optString("to", ""),
                    relation = obj.optString("relation", ""),
                )
            )
        }
        return edges.filter { it.from.isNotBlank() && it.to.isNotBlank() }
    }
}
