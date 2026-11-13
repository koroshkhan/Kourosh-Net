package com.kourosh.netoptimizer

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    fun buildJsonFromVlessUri(vlessUri: String, cleanIpOverride: String = ""): String {
        val trimmed = vlessUri.trim()
        require(trimmed.startsWith("vless://")) { "لینک باید با vless:// شروع شود" }

        val uri = Uri.parse(trimmed)
        val uuid = uri.userInfo ?: throw IllegalArgumentException("UUID نامعتبر است")
        val origHost = uri.host ?: throw IllegalArgumentException("آدرس سرور نامعتبر است")
        val port = if (uri.port != -1) uri.port else 443

        val security = uri.getQueryParameter("security") ?: "tls"
        val netType = uri.getQueryParameter("type") ?: "ws"
        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: origHost
        val wsHost = uri.getQueryParameter("host") ?: sni
        val wsPath = uri.getQueryParameter("path") ?: "/"
        val fp = uri.getQueryParameter("fp") ?: "chrome"
        val pbk = uri.getQueryParameter("pbk") ?: ""
        val sid = uri.getQueryParameter("sid") ?: ""
        val flow = uri.getQueryParameter("flow") ?: ""

        val finalServerAddress = if (cleanIpOverride.isNotBlank() && netType == "ws") {
            cleanIpOverride.trim()
        } else {
            origHost
        }

        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        root.put(
            "dns",
            JSONObject().put(
                "servers",
                JSONArray().put("https://1.1.1.1/dns-query").put("8.8.8.8")
            )
        )

        val inbounds = JSONArray()
        inbounds.put(
            JSONObject()
                .put("tag", "socks-in")
                .put("port", 10808)
                .put("listen", "127.0.0.1")
                .put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
        )
        inbounds.put(
            JSONObject()
                .put("tag", "http-in")
                .put("port", 10809)
                .put("listen", "127.0.0.1")
                .put("protocol", "http")
        )
        root.put("inbounds", inbounds)

        val streamSettings = JSONObject()
            .put("network", netType)
            .put("security", security)
            .put(
                "sockopt",
                JSONObject()
                    .put("dialerProxy", "fragment-out")
                    .put("tcpNoDelay", true)
            )

        if (security == "tls") {
            streamSettings.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", sni)
                    .put("allowInsecure", true)
                    .put("fingerprint", fp)
            )
        } else if (security == "reality") {
            streamSettings.put(
                "realitySettings",
                JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", fp)
                    .put("publicKey", pbk)
                    .put("shortId", sid)
            )
        }

        if (netType == "ws") {
            streamSettings.put(
                "wsSettings",
                JSONObject()
                    .put("path", wsPath)
                    .put("headers", JSONObject().put("Host", wsHost))
            )
        }

        val userObj = JSONObject()
            .put("id", uuid)
            .put("encryption", "none")
        if (flow.isNotEmpty()) userObj.put("flow", flow)

        val vnextObj = JSONObject()
            .put("address", finalServerAddress)
            .put("port", port)
            .put("users", JSONArray().put(userObj))

        val proxyOutbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnextObj)))
            .put("streamSettings", streamSettings)

        val fragmentOutbound = JSONObject()
            .put("tag", "fragment-out")
            .put("protocol", "freedom")
            .put(
                "settings",
                JSONObject().put(
                    "fragment",
                    JSONObject()
                        .put("packets", "tlshello")
                        .put("length", "10-20")
                        .put("interval", "10-15")
                )
            )
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tcpNoDelay", true)))

        val directOutbound = JSONObject()
            .put("tag", "direct")
            .put("protocol", "freedom")

        root.put(
            "outbounds",
            JSONArray()
                .put(proxyOutbound)
                .put(fragmentOutbound)
                .put(directOutbound)
        )

        return root.toString(2)
    }
}
