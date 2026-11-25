package com.phoebe.app.data

/**
 * A short, readable sign-in error. Network failures are named by what went wrong rather than by the raw
 * exception text, which can carry the full request URL (and, for Subsonic, the auth token and salt).
 */
fun signInErrorMessage(error: Throwable, providerName: String, serverUrl: String): String {
    val host = serverUrl.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
        .ifBlank { "the server" }
    val names = generateSequence(error) { it.cause }.take(8).map { it::class.simpleName.orEmpty() }.toList()
    fun causedBy(vararg fragments: String) = names.any { name -> fragments.any { name.contains(it, ignoreCase = true) } }
    return when {
        causedBy("UnknownHost", "UnresolvedAddress") ->
            "Couldn't find $host. Check the server address."
        causedBy("Timeout") ->
            "Couldn't reach $host. Check the address and port, and that the server is running and on this network."
        causedBy("ConnectException", "NoRouteToHost", "ConnectionRefused", "PortUnreachable") ->
            "$host didn't accept the connection. Check the port and that the server is running."
        causedBy("SSL", "CertPath", "Certificate") ->
            "Couldn't make a secure connection to $host. Check the server's certificate, or try http:// instead."
        else -> error.message?.takeIf { it.isSafeToShow() } ?: "Couldn't sign in to $providerName."
    }
}

private fun String.isSafeToShow(): Boolean =
    isNotBlank() && length <= 200 && !contains("://") && !contains("url=", ignoreCase = true)
