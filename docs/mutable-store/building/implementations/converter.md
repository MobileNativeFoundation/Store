# Converter

```kotlin
interface Converter<Network : Any, Common : Any, SOT : Any> {
    class Builder<Network : Any, Common : Any, SOT : Any> {
        fun build(): Converter<Network, Common, SOT> =
            RealConverter(
                fromNetworkToCommon = fromNetworkToCommon,
                fromCommonToSOT = fromCommonToSOT,
                from SOTToCommon = fromSOTToCommon
            )
    }
}
```

## Example

```kotlin
fun provide(): Converter<NetworkNote, CommonNote, Note> =
    Converter.Builder()
        .fromNetworkToCommon { network: Network ->
            CommonNote(
                id = network._id,
                authorId = network.authorId,
                title = network.title,
                content = network.content
            )
        }
        .fromCommonToSOT { common: Common ->
            Note(
                id = common.id,
                authorId = common.authorId,
                title = common.title,
                content = common.content,
                ttl = common.ttl ?: ttl()
            )
        }
        .fromSOTToCommon { sot: SOT ->
            CommonNote(
                id = sot.id,
                authorId = sot.authorId,
                title = sot.title,
                content = sot.content,
                ttl = sot.ttl
            )
        }
        .build()
```