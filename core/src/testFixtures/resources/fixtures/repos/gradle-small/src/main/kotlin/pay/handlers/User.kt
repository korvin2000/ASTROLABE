package pay.handlers

import pay.Request
import pay.Response

/** Answers a user lookup; takes the request only, like its Python twin. */
public fun handleUser(req: Request): Response =
    when (val id = req.userId) {
        null -> Response(400, "userId is required")
        else -> Response(200, "user $id")
    }
