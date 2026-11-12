package me.ash.reader.domain.model.account.security

class RemoteReaderSecurityKey(
    var serverUrl: String? = null,
    var username: String? = null,
    var password: String? = null,
    var accessToken: String? = null,
    var clientId: String? = null,
    var clientSecret: String? = null,
    var clientCertificateAlias: String? = null,
) : SecurityKey() {
    companion object {
        fun from(value: String?): RemoteReaderSecurityKey = object : SecurityKey() {}.decode(value, RemoteReaderSecurityKey::class.java)
    }
}
