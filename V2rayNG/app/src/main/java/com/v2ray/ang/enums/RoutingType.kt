package com.v2ray.ang.enums

enum class RoutingType(val fileName: String) {
    GLOBAL("custom_routing_global"),
    WHITE_IRAN("custom_routing_white_iran"),
    WHITE_RUSSIA("custom_routing_white_russia");

    companion object {
        fun fromIndex(index: Int): RoutingType {
            return when (index) {
                0 -> GLOBAL
                1 -> WHITE_IRAN
                2 -> WHITE_RUSSIA
                else -> GLOBAL
            }
        }
    }
}
