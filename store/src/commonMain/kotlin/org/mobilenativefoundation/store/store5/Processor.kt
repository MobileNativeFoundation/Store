package org.mobilenativefoundation.store.store5

typealias Processor<Output> = suspend (output: Output) -> Output
