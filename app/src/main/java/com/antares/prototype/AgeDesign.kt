package com.antares.prototype

import org.json.JSONObject

internal enum class AgeScope { FACE, FACE_AND_VISIBLE_BODY }

internal data class AgeDesign(val years: Int, val strength: Int, val scope: AgeScope) {
    init { require(years in -40..40 && strength in 0..100) }
    fun json() = JSONObject().put("version", 1).put("years", years).put("strength", strength)
        .put("scope", scope.name).put("classification", "simulated appearance")
    companion object {
        fun read(data: JSONObject): AgeDesign {
            require(data.getInt("version") == 1)
            return AgeDesign(data.getInt("years"), data.getInt("strength"), AgeScope.valueOf(data.getString("scope")))
        }
    }
}
