package pay

public data class Request(val path: String, val userId: Int? = null)

public data class Response(val status: Int, val body: String)

public val CURRENCIES: List<String> = listOf("EUR", "USD", "GBP")

public fun normalizeCurrency(raw: String): String {
    val code = raw.trim().uppercase()
    require(code in CURRENCIES) { "unknown currency: $raw" }
    return code.lowercase()
}

public class Router(private val routes: Map<String, (Request) -> Response>) {
    public fun route(req: Request): Response =
        routes[req.path]?.invoke(req) ?: Response(404, "no route for ${req.path}")

    public fun paths(): List<String> = routes.keys.sorted()
}
