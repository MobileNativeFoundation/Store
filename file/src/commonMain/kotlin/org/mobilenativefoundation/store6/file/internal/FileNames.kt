package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.files.Path

/**
 * On-disk name mapping for one `(namespace, canonicalId)` pair.
 *
 * Encoded components are lowercase unpadded RFC 4648 base32 of the UTF-8 bytes, with the
 * empty-string sentinel `"0"` from [Base32.encode]. Callers supply the subtree root
 * (`values/` or `records/`). This object composes child [Path] values and does not touch
 * the filesystem.
 */
internal object FileNames {
    /**
     * Maximum UTF-8 byte length of `namespace.value` and of `canonicalId()`, each.
     *
     * Base32 expands by 8/5, and `ceil(159 × 8 / 5) = 255`, which is the name-component
     * budget on ext4, APFS, and NTFS for these ASCII encodings.
     */
    const val MAX_COMPONENT_UTF8_BYTES: Int = 159

    /** Suffix appended to a file name to form its quarantine sibling. */
    const val CORRUPT_SUFFIX: String = ".corrupt"

    /**
     * Throws [IllegalArgumentException] when [namespace] or [canonicalId] is not well-formed
     * UTF-16, or when its UTF-8 byte length exceeds [MAX_COMPONENT_UTF8_BYTES].
     *
     * This is the one validation entry point for the strings a path is derived from. The
     * exception message names the offending part (`namespace` or `canonical id`). A malformed
     * message adds the index of the first unpaired surrogate; a length message adds the limit
     * (`159`) and the actual UTF-8 byte length. [namespace] is checked first, and each part is
     * checked for well-formedness before its length.
     *
     * Well-formedness is checked first because `String.encodeToByteArray` replaces an unpaired
     * surrogate with U+FFFD: distinct malformed strings would otherwise encode to the same
     * bytes, share an on-disk name, and be measured against the limit as their replacement
     * encoding rather than as themselves.
     *
     * Empty strings are well-formed and 0 bytes, so they are accepted. Their encoded names use
     * the `"0"` sentinel.
     */
    fun requireValidComponents(
        namespace: String,
        canonicalId: String,
    ) {
        requireWellFormed(namespace, "namespace")
        requireComponentLength(namespace, "namespace")
        requireWellFormed(canonicalId, "canonical id")
        requireComponentLength(canonicalId, "canonical id")
    }

    /**
     * Directory of one namespace under [subtreeRoot]: `<subtreeRoot>/<enc(namespace)>`.
     *
     * Does not validate [namespace]. Call [requireValidComponents] first when the string comes
     * from a key.
     */
    fun namespaceDirectory(
        subtreeRoot: Path,
        namespace: String,
    ): Path = Path(subtreeRoot, Base32.encode(namespace))

    /**
     * File of one key under [subtreeRoot]:
     * `<subtreeRoot>/<enc(namespace)>/<enc(canonicalId)>`.
     *
     * Does not validate its components. Call [requireValidComponents] first when the strings
     * come from a key.
     */
    fun keyPath(
        subtreeRoot: Path,
        namespace: String,
        canonicalId: String,
    ): Path = Path(subtreeRoot, Base32.encode(namespace), Base32.encode(canonicalId))

    /**
     * Quarantine sibling of [path]: the same parent, file name plus [CORRUPT_SUFFIX].
     *
     * `.` is outside the base32 alphabet, so this name cannot collide with a value or
     * record name. When [path] has no parent, the result is a relative path of the
     * suffixed file name alone.
     */
    fun corruptSibling(path: Path): Path {
        val name = path.name + CORRUPT_SUFFIX
        val parent = path.parent
        return if (parent != null) {
            Path(parent, name)
        } else {
            Path(name)
        }
    }

    private fun requireWellFormed(
        value: String,
        part: String,
    ) {
        val malformedIndex = firstMalformedIndex(value)
        require(malformedIndex < 0) {
            "$part contains a malformed UTF-16 sequence at index $malformedIndex"
        }
    }

    private fun requireComponentLength(
        value: String,
        part: String,
    ) {
        val byteLength = value.encodeToByteArray().size
        require(byteLength <= MAX_COMPONENT_UTF8_BYTES) {
            "$part UTF-8 byte length $byteLength exceeds limit $MAX_COMPONENT_UTF8_BYTES"
        }
    }

    /**
     * Index of the first unpaired surrogate in [value], or `-1` when every surrogate is paired.
     *
     * A high surrogate must be followed by a low surrogate, and a low surrogate must be
     * preceded by a high surrogate.
     */
    private fun firstMalformedIndex(value: String): Int {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isLowSurrogate()) return index
            if (!character.isHighSurrogate()) {
                index += 1
                continue
            }
            val lowIndex = index + 1
            if (lowIndex >= value.length || !value[lowIndex].isLowSurrogate()) return index
            index = lowIndex + 1
        }
        return -1
    }
}
