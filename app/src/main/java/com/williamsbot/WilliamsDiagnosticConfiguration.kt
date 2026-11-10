package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * A diagnostic report already stores all decision traces in `settings` and
 * separately in `signal_decision_history`. Repeating their multi-megabyte
 * mutable JSON under `configuration_sanitized` increased ZIP serialization
 * and memory pressure without adding forensic facts.
 *
 * This function never alters the source. It keeps ALL configuration and safety
 * keys (including risk, testnet, strategy and leverage), excluding only the
 * heavyweight *observational* decision_traces tree. Consumers needing the
 * full tree must read status.decision_traces or signal_decision_history.json.
 */
internal fun williamsDiagnosticConfigurationSnapshot(settings: JSONObject): JSONObject {
    val result = JSONObject()
    val keys = settings.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key != "decision_traces") result.put(key, settings.opt(key))
    }
    result.put("diagnostic_projection", "CONFIGURATION_ONLY_V1")
    result.put("omitted_observation_fields", JSONArray().put("decision_traces"))
    result.put("full_decision_trace_path", "status.decision_traces")
    return result
}
