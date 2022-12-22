# Overview

## 1. Define generics

- [Key](/mutable-store/generics/key) - unique identifier for `Item`
- [Network](/mutable-store/generics/network) - representation of `Item` on network
- [Common](/mutable-store/generics/common) - representation of `Item` into/out of `Store`
- [SOT](/mutable-store/generics/sot) - representation of `Item` in `SourceofTruth`
- [UpdaterResult](/mutable-store/generics/updater-result) - result from network update

## 2. Provide implementations

- [Fetcher](/mutable-store/implementations/fetcher)
- [SourceOfTruth](/mutable-store/implementations/source-of-truth)
- [Updater](/mutable-store/implementations/updater)
- [Bookkeeper](/mutable-store/implementations/bookkeeper)
- [Validator](/mutable-store/implementations/validator)
- [Converter](/mutable-store/implementations/converter)