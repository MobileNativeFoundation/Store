package org.mobilenativefoundation.store6.file

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Encodes one value as one file payload.
 *
 * Round-trip law: decode(encode(v)) must be structurally equal to v. Implementations must not close
 * or retain the supplied [Source] or [Sink] because the adapter owns them, must write the complete
 * payload before returning from [encode], and must treat the [Source] as exactly one payload. A
 * throw from [encode] fails the mutation and applies nothing. A throw from [decode] is handled
 * according to the configured [FileCorruptionPolicy].
 */
@ExperimentalStoreApi
public interface FileCodec<V : Any> {
    public fun encode(
        value: V,
        sink: Sink,
    )

    public fun decode(source: Source): V
}

@ExperimentalStoreApi
public object ByteArrayFileCodec : FileCodec<ByteArray> {
    public override fun encode(
        value: ByteArray,
        sink: Sink,
    ) {
        sink.write(value)
    }

    public override fun decode(source: Source): ByteArray = source.readByteArray()
}

@ExperimentalStoreApi
public object Utf8StringFileCodec : FileCodec<String> {
    public override fun encode(
        value: String,
        sink: Sink,
    ) {
        sink.writeString(value)
    }

    public override fun decode(source: Source): String = source.readString()
}
