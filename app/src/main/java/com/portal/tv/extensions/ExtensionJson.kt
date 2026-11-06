package com.portal.tv.extensions

import org.json.JSONArray
import org.json.JSONObject

/** How what was allowed is written to storage: one entry per extension package. */
object ExtensionJson {

    fun encode(allowances: Map<String, Allowance>): String {
        val arr = JSONArray()
        allowances.toSortedMap().forEach { (pkg, a) ->
            arr.put(JSONObject().put("package", pkg).put("signer", a.signer).put("capabilities", JSONArray(a.capabilities.toList())))
        }
        return arr.toString()
    }

    fun decode(json: String?): Map<String, Allowance> {
        if (json == null) return emptyMap()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                val caps = o.optJSONArray("capabilities")?.let { c -> (0 until c.length()).map { c.getString(it) }.toCollection(linkedSetOf()) } ?: linkedSetOf()
                o.getString("package") to Allowance(o.getString("signer"), caps)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
