/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing permissions and limitations
 * under the License.
 */

package com.dropbox.kmp.external.cache3

/**
 * Returns the weight of a cache entry. There is no unit for entry weights; rather they are simply
 * relative to each other.
 *
 * @return the weight of the entry; must be non-negative
 */
typealias Weigher <K, V> = (key: K, value: V) -> Int