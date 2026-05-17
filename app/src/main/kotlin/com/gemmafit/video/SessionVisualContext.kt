package com.gemmafit.video

import org.json.JSONObject

data class SessionVisualContext(
    val env: String = ENV_UNKNOWN,
    val support: String = SUPPORT_UNKNOWN,
    val person: String = PERSON_UNKNOWN,
    val overlayReadable: Boolean? = null,
    val limited: Boolean? = null,
    val source: String = SOURCE_NONE,
    val evidenceRefs: List<String> = emptyList(),
    val rawResponse: String = "",
) {
    val available: Boolean
        get() = env != ENV_UNKNOWN ||
            support != SUPPORT_UNKNOWN ||
            person != PERSON_UNKNOWN ||
            overlayReadable != null ||
            limited != null

    fun toJson(): JSONObject {
        return JSONObject()
            .put("env", env)
            .put("support", support)
            .put("person", person)
            .put("overlay_readable", overlayReadable ?: JSONObject.NULL)
            .put("limited", limited ?: JSONObject.NULL)
            .put("source", source)
            .put("evidence_refs", org.json.JSONArray(evidenceRefs.take(6)))
    }

    companion object {
        const val ENV_INDOOR = "indoor"
        const val ENV_OUTDOOR = "outdoor"
        const val ENV_UNKNOWN = "unknown"
        const val SUPPORT_CHAIR = "chair"
        const val SUPPORT_NONE = "none"
        const val SUPPORT_UNKNOWN = "unknown"
        const val PERSON_VISIBLE = "visible"
        const val PERSON_NOT_VISIBLE = "not_visible"
        const val PERSON_MULTIPLE = "multiple"
        const val PERSON_UNKNOWN = "unknown"
        const val SOURCE_NONE = "none"
        const val SOURCE_LITERT_VISION = "litert_vision_sidecar"
        const val REF_ENV = "visual_context.env"
        const val REF_SUPPORT = "visual_context.support"
        const val REF_PERSON = "visual_context.person"
        const val REF_OVERLAY = "visual_context.overlay_readable"
        const val REF_LIMITED = "visual_context.limited"

        fun unknown(source: String = SOURCE_NONE): SessionVisualContext =
            SessionVisualContext(source = source)
    }
}

object SessionVisualContextParser {
    fun parse(raw: String, source: String = SessionVisualContext.SOURCE_LITERT_VISION): SessionVisualContext {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return SessionVisualContext.unknown(source)
        parseJsonObject(trimmed)?.let { return fromJson(it, source, trimmed) }

        val pairs = trimmed
            .lineSequence()
            .flatMap { it.splitToSequence(';') }
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@mapNotNull null
                part.substring(0, index).trim().lowercase() to
                    part.substring(index + 1).trim().trim('"', '\'', ',', ' ')
            }
            .toMap()

        return SessionVisualContext(
            env = normalizeEnv(pairs["env"]),
            support = normalizeSupport(pairs["support"]),
            person = normalizePerson(pairs["person"]),
            overlayReadable = normalizeBoolean(pairs["overlay_readable"]),
            limited = normalizeBoolean(pairs["limited"]),
            source = source,
            evidenceRefs = evidenceRefsFor(pairs),
            rawResponse = trimmed.take(1_000),
        )
    }

    private fun fromJson(json: JSONObject, source: String, raw: String): SessionVisualContext {
        return SessionVisualContext(
            env = normalizeEnv(json.optString("env")),
            support = normalizeSupport(json.optString("support")),
            person = normalizePerson(json.optString("person")),
            overlayReadable = normalizeBoolean(json.opt("overlay_readable")),
            limited = normalizeBoolean(json.opt("limited")),
            source = source,
            evidenceRefs = evidenceRefsFor(
                mapOf(
                    "env" to json.optString("env"),
                    "support" to json.optString("support"),
                    "person" to json.optString("person"),
                    "overlay_readable" to json.optString("overlay_readable"),
                    "limited" to json.optString("limited"),
                ),
            ),
            rawResponse = raw.take(1_000),
        )
    }

    private fun parseJsonObject(raw: String): JSONObject? {
        runCatching { return JSONObject(raw) }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    private fun normalizeEnv(value: String?): String {
        return normalizedChoice(value, unknown = SessionVisualContext.ENV_UNKNOWN) { token ->
            when (token) {
                "indoor", "indoors", "inside" -> SessionVisualContext.ENV_INDOOR
                "outdoor", "outdoors", "outside" -> SessionVisualContext.ENV_OUTDOOR
                else -> null
            }
        }
    }

    private fun normalizeSupport(value: String?): String {
        return normalizedChoice(value, unknown = SessionVisualContext.SUPPORT_UNKNOWN) { token ->
            when (token) {
                "chair", "seat", "bench", "stool" -> SessionVisualContext.SUPPORT_CHAIR
                "none", "no", "false", "absent" -> SessionVisualContext.SUPPORT_NONE
                else -> null
            }
        }
    }

    private fun normalizePerson(value: String?): String {
        return normalizedChoice(value, unknown = SessionVisualContext.PERSON_UNKNOWN) { token ->
            when (token) {
                "visible", "person_visible", "single", "1", "one" -> SessionVisualContext.PERSON_VISIBLE
                "not_visible", "not visible", "none", "0", "absent" -> SessionVisualContext.PERSON_NOT_VISIBLE
                "multiple", "multi", "many", "2", "two" -> SessionVisualContext.PERSON_MULTIPLE
                else -> null
            }
        }
    }

    private fun normalizedChoice(
        value: String?,
        unknown: String,
        mapper: (String) -> String?,
    ): String {
        val normalized = value?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return unknown
        mapper(normalized)?.let { return it }
        if ('|' !in normalized) return unknown
        val mapped = normalized
            .split('|')
            .map { it.trim() }
            .mapNotNull(mapper)
            .distinct()
        return mapped.singleOrNull() ?: unknown
    }

    private fun normalizeBoolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "y" -> true
                "false", "0", "no", "n" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun evidenceRefsFor(values: Map<String, String?>): List<String> {
        return buildList {
            if (normalizeEnv(values["env"]) != SessionVisualContext.ENV_UNKNOWN) add(SessionVisualContext.REF_ENV)
            if (normalizeSupport(values["support"]) != SessionVisualContext.SUPPORT_UNKNOWN) add(SessionVisualContext.REF_SUPPORT)
            if (normalizePerson(values["person"]) != SessionVisualContext.PERSON_UNKNOWN) add(SessionVisualContext.REF_PERSON)
            if (normalizeBoolean(values["overlay_readable"]) != null) add(SessionVisualContext.REF_OVERLAY)
            if (normalizeBoolean(values["limited"]) != null) add(SessionVisualContext.REF_LIMITED)
        }
    }
}
