@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GraphQlCanonicalizationTest {
    @Test
    fun canonicalId_pinsNameAndSortedVariableRendering() {
        val key =
            GraphQlOperationKey(
                operationName = "GetUser",
                variables =
                    graphQlVariables {
                        put("limit", 10)
                        put("id", "42")
                    },
            )

        assertEquals("GetUser({\"id\":\"42\",\"limit\":10})", key.canonicalId())
    }

    @Test
    fun canonicalId_emptyVariablesRenderAsEmptyObject() {
        val key = GraphQlOperationKey(operationName = "GetUser")

        assertEquals("GetUser({})", key.canonicalId())
    }

    @Test
    fun canonicalId_topLevelInsertionOrderIsIrrelevant() {
        val ab =
            GraphQlOperationKey(
                operationName = "Search",
                variables =
                    graphQlVariables {
                        put("a", 1)
                        put("b", 2)
                    },
            )
        val ba =
            GraphQlOperationKey(
                operationName = "Search",
                variables =
                    graphQlVariables {
                        put("b", 2)
                        put("a", 1)
                    },
            )

        assertEquals(ab.canonicalId(), ba.canonicalId())
        assertEquals(ab, ba)
        assertEquals(ab.hashCode(), ba.hashCode())
    }

    @Test
    fun canonicalId_nestedObjectKeysAreSortedRecursively() {
        val key =
            GraphQlOperationKey(
                operationName = "Search",
                variables =
                    graphQlVariables {
                        putObject("filter") {
                            put("b", 1)
                            put("a", 2)
                        }
                    },
            )

        assertEquals("Search({\"filter\":{\"a\":2,\"b\":1}})", key.canonicalId())
    }

    @Test
    fun canonicalId_objectKeysSortByUtf16CodeUnitOrder() {
        // 'Z' (0x5A) < '_' (0x5F) < 'a' (0x61): code-unit order, not case-insensitive order.
        val key =
            GraphQlOperationKey(
                operationName = "Q",
                variables =
                    graphQlVariables {
                        put("a", 1)
                        put("Z", 2)
                        put("_", 3)
                    },
            )

        assertEquals("Q({\"Z\":2,\"_\":3,\"a\":1})", key.canonicalId())
    }

    @Test
    fun canonicalId_listOrderIsSignificant() {
        val oneTwo =
            GraphQlOperationKey(
                operationName = "Q",
                variables =
                    graphQlVariables {
                        putList("ids") {
                            add(1)
                            add(2)
                        }
                    },
            )
        val twoOne =
            GraphQlOperationKey(
                operationName = "Q",
                variables =
                    graphQlVariables {
                        putList("ids") {
                            add(2)
                            add(1)
                        }
                    },
            )

        assertEquals("Q({\"ids\":[1,2]})", oneTwo.canonicalId())
        assertEquals("Q({\"ids\":[2,1]})", twoOne.canonicalId())
        assertNotEquals(oneTwo, twoOne)
    }

    @Test
    fun canonicalId_stringsUseJsonEscaping() {
        val key =
            GraphQlOperationKey(
                operationName = "Q",
                variables =
                    graphQlVariables {
                        put("s", "he said \"hi\"\nthen\ta\\slash")
                    },
            )

        assertEquals("Q({\"s\":\"he said \\\"hi\\\"\\nthen\\ta\\\\slash\"})", key.canonicalId())
    }

    @Test
    fun canonicalId_controlCharactersUseShortOrHexEscapes() {
        val key =
            GraphQlOperationKey(
                operationName = "Q",
                variables =
                    graphQlVariables {
                        put("s", "\b\u000C\n\r\t\u0000\u001F")
                    },
            )

        assertEquals("Q({\"s\":\"\\b\\f\\n\\r\\t\\u0000\\u001f\"})", key.canonicalId())
    }

    @Test
    fun canonicalId_explicitNullIsDistinctFromAbsent() {
        val explicitNull =
            GraphQlOperationKey(
                operationName = "Q",
                variables = graphQlVariables { putNull("cursor") },
            )
        val absent = GraphQlOperationKey(operationName = "Q")

        assertEquals("Q({\"cursor\":null})", explicitNull.canonicalId())
        assertEquals("Q({})", absent.canonicalId())
        assertNotEquals(explicitNull, absent)
    }

    @Test
    fun canonicalId_scalarRenderings() {
        val key =
            GraphQlOperationKey(
                operationName = "Q",
                variables =
                    graphQlVariables {
                        put("yes", true)
                        put("no", false)
                        put("big", Long.MAX_VALUE)
                        put("half", 1.5)
                    },
            )

        assertEquals(
            "Q({\"big\":9223372036854775807,\"half\":1.5,\"no\":false,\"yes\":true})",
            key.canonicalId(),
        )
    }

    @Test
    fun canonicalId_duplicatePutsLastWins() {
        val key =
            GraphQlOperationKey(
                operationName = "Q",
                variables =
                    graphQlVariables {
                        put("a", 1)
                        put("a", 2)
                    },
            )

        assertEquals("Q({\"a\":2})", key.canonicalId())
    }

    @Test
    fun canonicalId_nestedListsAndObjectsCompose() {
        val key =
            GraphQlOperationKey(
                operationName = "Q",
                variables =
                    graphQlVariables {
                        putList("rows") {
                            addObject {
                                put("y", 1)
                                put("x", 2)
                            }
                            addList {
                                add("s")
                                addNull()
                            }
                        }
                    },
            )

        assertEquals("Q({\"rows\":[{\"x\":2,\"y\":1},[\"s\",null]]})", key.canonicalId())
    }

    @Test
    fun namespace_defaultsToGraphqlPrefixedOperationName() {
        val key = GraphQlOperationKey(operationName = "GetUser")

        assertEquals("graphql:GetUser", key.namespace.value)
    }

    @Test
    fun namespace_customNamespaceIsPreservedAndPartOfIdentity() {
        val custom =
            GraphQlOperationKey(
                operationName = "GetUser",
                namespace = StoreNamespace("tenant-a"),
            )
        val default = GraphQlOperationKey(operationName = "GetUser")

        assertEquals("tenant-a", custom.namespace.value)
        assertNotEquals(custom, default)
    }

    @Test
    fun variables_equalityIsStructural() {
        val ab =
            graphQlVariables {
                put("a", 1)
                put("b", "x")
            }
        val ba =
            graphQlVariables {
                put("b", "x")
                put("a", 1)
            }

        assertEquals(ab, ba)
        assertEquals(ab.hashCode(), ba.hashCode())
        assertNotEquals(ab, graphQlVariables { put("a", 1) })
    }

    @Test
    fun values_intAndFloatAreDistinct() {
        assertNotEquals<GraphQlValue>(GraphQlValue.IntValue(1), GraphQlValue.FloatValue(1.0))
        assertEquals<GraphQlValue>(GraphQlValue.IntValue(1), GraphQlValue.IntValue(1))
    }

    @Test
    fun values_signedZeroIsStoredAsPositiveZero() {
        assertEquals(0.0.toBits(), GraphQlValue.FloatValue(0.0).value.toBits())
        assertEquals(0.0.toBits(), GraphQlValue.FloatValue(-0.0).value.toBits())
    }

    @Test
    fun values_signedZeroHasEqualHashCodes() {
        val positive = GraphQlValue.FloatValue(0.0)
        val negative = GraphQlValue.FloatValue(-0.0)

        assertEquals(positive, negative)
        assertEquals(negative, positive)
        assertEquals(positive.hashCode(), negative.hashCode())
    }

    @Test
    fun variables_signedZeroHasEqualHashCodes() {
        val positive = graphQlVariables { put("value", 0.0) }
        val negative = graphQlVariables { put("value", -0.0) }

        assertEquals(positive, negative)
        assertEquals(positive.hashCode(), negative.hashCode())
    }

    @Test
    fun operationKeys_signedZeroHasEqualHashCodes() {
        val positive = GraphQlOperationKey("Q", graphQlVariables { put("value", 0.0) })
        val negative = GraphQlOperationKey("Q", graphQlVariables { put("value", -0.0) })

        assertEquals(positive, negative)
        assertEquals(positive.hashCode(), negative.hashCode())
    }

    @Test
    fun canonicalId_signedZeroHasOneFloatIdentity() {
        val positive = GraphQlOperationKey("Q", graphQlVariables { put("value", 0.0) })
        val negative = GraphQlOperationKey("Q", graphQlVariables { put("value", -0.0) })

        assertEquals("Q({\"value\":0.0})", positive.canonicalId())
        assertEquals(positive.canonicalId(), negative.canonicalId())
    }

    @Test
    fun values_signedZeroDeduplicatesInHashSetsThroughOperationKeys() {
        val positive: GraphQlValue = GraphQlValue.FloatValue(0.0)
        val negative: GraphQlValue = GraphQlValue.FloatValue(-0.0)
        val positiveVariables = GraphQlVariables(mapOf("value" to positive))
        val negativeVariables = GraphQlVariables(mapOf("value" to negative))
        val positiveKey = GraphQlOperationKey("Q", positiveVariables)
        val negativeKey = GraphQlOperationKey("Q", negativeVariables)

        assertEquals(hashSetOf(positive), hashSetOf(negative))
        assertEquals(1, hashSetOf(positive, negative).size)
        assertEquals(hashSetOf(positiveVariables), hashSetOf(negativeVariables))
        assertEquals(1, hashSetOf(positiveVariables, negativeVariables).size)
        assertEquals(hashSetOf(positiveKey), hashSetOf(negativeKey))
        assertEquals(1, hashSetOf(positiveKey, negativeKey).size)
    }

    @Test
    fun values_nanIsRejectedAtConstructionAndByBuilders() {
        assertNonfiniteRejected(Double.NaN)
    }

    @Test
    fun values_positiveInfinityIsRejectedAtConstructionAndByBuilders() {
        assertNonfiniteRejected(Double.POSITIVE_INFINITY)
    }

    @Test
    fun values_negativeInfinityIsRejectedAtConstructionAndByBuilders() {
        assertNonfiniteRejected(Double.NEGATIVE_INFINITY)
    }

    @Test
    fun values_intAndWholeFloatRemainDistinctThroughOperationKeyHashSets() {
        for (number in listOf(0L, 1L, -1L, 1_000_000L)) {
            val integer: GraphQlValue = GraphQlValue.IntValue(number)
            val float: GraphQlValue = GraphQlValue.FloatValue(number.toDouble())
            val integerVariables = GraphQlVariables(mapOf("value" to integer))
            val floatVariables = GraphQlVariables(mapOf("value" to float))
            val integerKey = GraphQlOperationKey("Q", integerVariables)
            val floatKey = GraphQlOperationKey("Q", floatVariables)

            assertNotEquals(integer, float)
            assertEquals(2, hashSetOf(integer, float).size)
            assertNotEquals(integerVariables, floatVariables)
            assertEquals(2, hashSetOf(integerVariables, floatVariables).size)
            assertNotEquals(integerKey, floatKey)
            assertEquals(2, hashSetOf(integerKey, floatKey).size)
        }
    }

    @Test
    fun canonicalId_intAndWholeFloatRemainDistinct() {
        for (number in listOf(0L, 1L, -1L, 1_000_000L)) {
            val integer = GraphQlOperationKey("Q", graphQlVariables { put("value", number) })
            val float = GraphQlOperationKey("Q", graphQlVariables { put("value", number.toDouble()) })

            assertEquals("Q({\"value\":$number})", integer.canonicalId())
            assertEquals("Q({\"value\":$number.0})", float.canonicalId())
            assertNotEquals(integer.canonicalId(), float.canonicalId())
        }
    }

    @Test
    fun values_finiteFloatsRetainTheirValueAndStructuralIdentity() {
        for (number in listOf(Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, 1.5, -1.5)) {
            val first: GraphQlValue = GraphQlValue.FloatValue(number)
            val second = GraphQlValue.FloatValue(number)
            val firstVariables = GraphQlVariables(mapOf("value" to first))
            val secondVariables = GraphQlVariables(mapOf("value" to second))
            val firstKey = GraphQlOperationKey("Q", firstVariables)
            val secondKey = GraphQlOperationKey("Q", secondVariables)

            assertEquals(number.toBits(), second.value.toBits())
            assertEquals(first, second)
            assertEquals(first.hashCode(), second.hashCode())
            assertEquals(1, hashSetOf(first, second).size)
            assertEquals(firstVariables, secondVariables)
            assertEquals(firstVariables.hashCode(), secondVariables.hashCode())
            assertEquals(1, hashSetOf(firstVariables, secondVariables).size)
            assertEquals(firstKey, secondKey)
            assertEquals(firstKey.hashCode(), secondKey.hashCode())
            assertEquals(firstKey.canonicalId(), secondKey.canonicalId())
            assertEquals(1, hashSetOf(firstKey, secondKey).size)
        }
    }

    @Test
    fun canonicalId_scalarAndContainerTypesRemainDistinct() {
        val values =
            listOf(
                GraphQlValue.IntValue(0),
                GraphQlValue.FloatValue(0.0),
                GraphQlValue.StringValue("0"),
                GraphQlValue.StringValue("0.0"),
                GraphQlValue.BooleanValue(false),
                GraphQlValue.BooleanValue(true),
                GraphQlValue.NullValue,
                GraphQlValue.ListValue(emptyList()),
                GraphQlValue.ObjectValue(emptyMap()),
            )
        val variables = values.map { GraphQlVariables(mapOf("value" to it)) }
        val keys = variables.map { GraphQlOperationKey("Q", it) }

        assertEquals(values.size, values.toHashSet().size)
        assertEquals(values.size, variables.toHashSet().size)
        assertEquals(values.size, keys.toHashSet().size)
        assertEquals(values.size, keys.map { it.canonicalId() }.toHashSet().size)
        assertNotEquals(GraphQlOperationKey("Q").canonicalId(), keys[6].canonicalId())
    }

    @Test
    fun canonicalId_nestedSignedZeroPreservesObjectAndListSemantics() {
        val positive =
            graphQlVariables {
                putObject("filter") {
                    put("flag", true)
                    putList("values") {
                        add(0.0)
                        addObject { put("zero", 0.0) }
                        addNull()
                    }
                }
            }
        val negative =
            graphQlVariables {
                putObject("filter") {
                    putList("values") {
                        add(-0.0)
                        addObject { put("zero", -0.0) }
                        addNull()
                    }
                    put("flag", true)
                }
            }
        val positiveKey = GraphQlOperationKey("Q", positive)
        val negativeKey = GraphQlOperationKey("Q", negative)

        assertEquals(positive, negative)
        assertEquals(positive.hashCode(), negative.hashCode())
        assertEquals(1, hashSetOf(positive, negative).size)
        assertEquals(positiveKey, negativeKey)
        assertEquals(positiveKey.hashCode(), negativeKey.hashCode())
        assertEquals(1, hashSetOf(positiveKey, negativeKey).size)
        assertEquals(positiveKey.canonicalId(), negativeKey.canonicalId())
        assertEquals(
            "Q({\"filter\":{\"flag\":true,\"values\":[0.0,{\"zero\":0.0},null]}})",
            positiveKey.canonicalId(),
        )
    }

    private fun assertNonfiniteRejected(number: Double) {
        assertFailsWith<IllegalArgumentException> { GraphQlValue.FloatValue(number) }
        assertFailsWith<IllegalArgumentException> { graphQlVariables { put("value", number) } }
        assertFailsWith<IllegalArgumentException> {
            graphQlVariables { putObject("filter") { put("value", number) } }
        }
        assertFailsWith<IllegalArgumentException> {
            graphQlVariables { putList("values") { add(number) } }
        }
    }

    @Test
    fun operationKeyHelper_carriesOperationNameAndVariables() {
        val operation =
            GraphQlOperation(
                document = "query GetUser(\$id: ID!) { user(id: \$id) { name } }",
                name = "GetUser",
            )
        val key = operation.key(graphQlVariables { put("id", "42") })

        assertEquals("GetUser", key.operationName)
        assertEquals("GetUser({\"id\":\"42\"})", key.canonicalId())
        assertEquals("graphql:GetUser", key.namespace.value)
    }
}
