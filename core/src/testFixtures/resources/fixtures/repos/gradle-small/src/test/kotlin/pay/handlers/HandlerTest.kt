package pay.handlers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pay.Request

// `smoke` also exists in pay.RouterTest (D-27, IX-13).
class HandlerTest {
    @Test
    fun smoke() {
        assertEquals(200, handleUser(Request("/user", userId = 7)).status)
    }

    @Test
    fun `missing userId is 400`() {
        assertEquals(400, handleUser(Request("/user")).status)
    }
}
