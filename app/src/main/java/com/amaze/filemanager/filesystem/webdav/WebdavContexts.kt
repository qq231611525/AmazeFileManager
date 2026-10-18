/*
 * Copyright (C) 2026 Contributors to Amaze File Manager.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.filesystem.webdav

import android.net.Uri
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * Connection pool for WebDAV servers, following the same pattern as
 * [com.amaze.filemanager.filesystem.smb.CifsContexts].
 *
 * Caches one OkHttpClient per server (scheme + authority + credentials),
 * so we don't rebuild the client for every file operation.
 */
object WebdavContexts {
    const val WEBDAV_URI_PREFIX = "webdav://"
    const val WEBDAVS_URI_PREFIX = "webdavs://"

    private val TAG = WebdavContexts::class.java.simpleName

    private val clients: MutableMap<String, OkHttpClient> = ConcurrentHashMap()

    /**
     * Returns a cached OkHttpClient for the given WebDAV server, creating one
     * if necessary. The client has Basic auth pre-configured when username is
     * not empty.
     *
     * @param basePath any webdav:// or webdavs:// URI on the target server
     * @param username WebDAV username (may be empty for anonymous access)
     * @param password WebDAV password
     */
    @JvmStatic
    fun getClient(
        basePath: String,
        username: String?,
        password: String?,
    ): OkHttpClient {
        val key = buildKey(basePath, username)
        return clients.getOrPut(key) {
            Log.d(TAG, "Creating new OkHttpClient for $key")
            val builder =
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true)
            if (!username.isNullOrEmpty()) {
                val credential = Credentials.basic(username, password ?: "")
                builder.addInterceptor { chain ->
                    val request =
                        chain.request().newBuilder()
                            .header("Authorization", credential)
                            .build()
                    chain.proceed(request)
                }
            }
            builder.build()
        }
    }

    /**
     * Converts a webdav:// or webdavs:// URI to the real http(s) URL that
     * dav4jvm needs.
     */
    @JvmStatic
    fun toHttpUrl(webdavUri: String): String {
        return when {
            webdavUri.startsWith(WEBDAV_URI_PREFIX) ->
                "http://" + webdavUri.removePrefix(WEBDAV_URI_PREFIX)
            webdavUri.startsWith(WEBDAVS_URI_PREFIX) ->
                "https://" + webdavUri.removePrefix(WEBDAVS_URI_PREFIX)
            else -> webdavUri
        }
    }

    /**
     * Converts an http(s) URL back to a webdav(s):// URI for display and
     * internal routing.
     */
    @JvmStatic
    fun toWebdavUri(httpUrl: String): String {
        return when {
            httpUrl.startsWith("https://") ->
                WEBDAVS_URI_PREFIX + httpUrl.removePrefix("https://")
            httpUrl.startsWith("http://") ->
                WEBDAV_URI_PREFIX + httpUrl.removePrefix("http://")
            else -> httpUrl
        }
    }

    @JvmStatic
    fun isWebdavUri(path: String): Boolean {
        return path.startsWith(WEBDAV_URI_PREFIX) || path.startsWith(WEBDAVS_URI_PREFIX)
    }

    @JvmStatic
    fun clearClients() {
        clients.forEach {
            try {
                it.value.dispatcher.executorService.shutdown()
                it.value.connectionPool.evictAll()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing WebDAV client", e)
            }
        }
        clients.clear()
    }

    private fun buildKey(
        basePath: String,
        username: String?,
    ): String {
        val uri = Uri.parse(toHttpUrl(basePath))
        val userPart = if (username.isNullOrEmpty()) "" else "$username@"
        return "${uri.scheme}://$userPart${uri.authority}"
    }
}
