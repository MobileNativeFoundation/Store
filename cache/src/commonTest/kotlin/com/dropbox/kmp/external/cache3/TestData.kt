package com.dropbox.kmp.external.cache3

class TypeWithHashCode(
    val x: String,
    val y: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as TypeWithHashCode

        if (x != other.x) return false
        if (y != other.y) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y
        return result
    }
}

class TypeWithoutHashCode(
    val x: String,
    val y: Int,
)
