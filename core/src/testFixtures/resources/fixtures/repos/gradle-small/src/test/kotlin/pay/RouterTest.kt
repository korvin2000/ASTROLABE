package pay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pay.handlers.handleUser

// `smoke` also exists in pay.handlers.HandlerTest: two classes in two
// packages, one method name, two distinct namespaced identities (D-27, IX-13).
class RouterTest {
    private val router = Router(mapOf("/user" to ::handleUser))

    @Test
    fun smoke() {
        assertEquals(200, router.route(Request("/user", userId = 7)).status)
    }

    @Test
    fun `unknown path is 404`() {
        assertEquals(404, router.route(Request("/nope")).status)
    }

    @ParameterizedTest
    @ValueSource(strings = ["EUR", "Usd", " gbp "])
    fun normalizesCurrency(raw: String) {
        assertEquals(raw.trim().lowercase(), normalizeCurrency(raw))
    }
}
