# Validator

```kotlin
interface Validator<Common : Any> {
    fun isValid(item: Common): Boolean
}
```

## Example

```kotlin
class RealValidator<Common : Any>(
    private val expiration: Long
) : Validator<Common> {
    override fun isValid(item: Common) = if (item.ttl == null) {
        true
    } else {
        item.ttl < expiration
    }
} 
```