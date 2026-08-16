@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
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
