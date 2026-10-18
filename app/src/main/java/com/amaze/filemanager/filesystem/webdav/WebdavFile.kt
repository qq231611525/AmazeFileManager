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

import android.util.Log
import android.util.Xml
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser

/**
 * Minimal WebDAV client built on OkHttp (no external WebDAV library).
 * Exposes the file operations Amaze's [com.amaze.filemanager.filesystem.HybridFile]
 * needs: list, download, upload, mkdir, delete, move.
 *
 * Uses raw HTTP methods: PROPFIND (depth 1 for listing), GET, PUT,
 * MKCOL, DELETE, MOVE. Parses PROPFIND multistatus XML responses.
 */
class WebdavFile(
    webdavUri: String,
    username: String?,
    password: String?,
) {
    private val tag = WebdavFile::class.java.simpleName

    private val httpUrl = WebdavContexts.toHttpUrl(webdavUri).toHttpUrl()
    private val client = WebdavContexts.getClient(webdavUri, username, password)

    /** Simple stat info for one remote entry. */
    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        /** webdav:// URI of this entry */
        val uri: String,
    )

    companion object {
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:getcontentlength/>
    <D:getcontenttype/>
    <D:getlastmodified/>
    <D:resourcetype/>
  </D:prop>
</D:propfind>"""

        private val HTTP_DATE_FORMATS = listOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
        ).apply {
            forEach { it.timeZone = TimeZone.getTimeZone("GMT") }
        }

        private fun parseHttpDate(dateStr: String?): Long {
            if (dateStr.isNullOrEmpty()) return 0L
            for (format in HTTP_DATE_FORMATS) {
                try {
                    synchronized(format) {
                        return format.parse(dateStr)?.time ?: 0L
                    }
                } catch (e: Exception) {
                    // Try next format
                }
            }
            return 0L
        }
    }

    /**
     * Lists the direct children of this collection (depth 1).
     * Returns empty list on error; check logs for details.
     */
    @Throws(IOException::class)
    fun list(): List<Entry> {
        val request = Request.Builder()
            .url(httpUrl)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaTypeOrNull()))
            .header("Depth", "1")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("PROPFIND failed: ${response.code} for $httpUrl")
            }
            val body = response.body?.string()
                ?: throw IOException("Empty PROPFIND response for $httpUrl")
            return parsePropfindResponse(body)
        }
    }

    private fun parsePropfindResponse(xml: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())

            var eventType = parser.eventType
            var currentHref: String? = null
            var currentDisplayName: String? = null
            var currentContentLength: Long = 0L
            var currentLastModified: Long = 0L
            var currentIsCollection = false
            var inResponse = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentDisplayName = null
                                currentContentLength = 0L
                                currentLastModified = 0L
                                currentIsCollection = false
                            }
                            "href" -> if (inResponse) {
                                currentHref = parser.nextText()
                            }
                            "displayname" -> if (inResponse) {
                                currentDisplayName = parser.nextText()
                            }
                            "getcontentlength" -> if (inResponse) {
                                currentContentLength = parser.nextText()?.toLongOrNull() ?: 0L
                            }
                            "getlastmodified" -> if (inResponse) {
                                currentLastModified = parseHttpDate(parser.nextText())
                            }
                            "collection" -> if (inResponse) {
                                currentIsCollection = true
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response" && inResponse) {
                            inResponse = false
                            // Skip the collection itself (first response)
                            // The href of the collection itself ends with the same path
                            val href = currentHref ?: ""
                            val basePath = httpUrl.encodedPath.trimEnd('/')
                            val hrefPath = href.trimEnd('/')

                            // Skip if this is the parent collection itself
                            if (hrefPath != basePath && hrefPath.isNotEmpty()) {
                                val name = currentDisplayName
                                    ?: href.substringAfterLast("/").takeIf { it.isNotEmpty() }
                                    ?: href.trimEnd('/').substringAfterLast("/")

                                if (name.isNotEmpty()) {
                                    val childHttpUrl = httpUrl.toString().trimEnd('/') + "/" + name
                                    entries.add(
                                        Entry(
                                            name = name,
                                            isDirectory = currentIsCollection,
                                            size = currentContentLength,
                                            lastModified = currentLastModified,
                                            uri = WebdavContexts.toWebdavUri(childHttpUrl),
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse PROPFIND response", e)
            throw IOException("Failed to parse PROPFIND response", e)
        }
        return entries
    }

    /** Downloads the remote file into [output]. */
    @Throws(IOException::class)
    fun download(output: OutputStream) {
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET failed: ${response.code} for $httpUrl")
            }
            response.body?.byteStream()?.use { input ->
                input.copyTo(output)
            } ?: throw IOException("Empty response body for $httpUrl")
        }
    }

    /** Uploads [input] to this path, creating or overwriting the remote file. */
    @Throws(IOException::class)
    fun upload(
        input: InputStream,
        contentLength: Long,
        contentType: String = "application/octet-stream",
    ) {
        val body = input.readBytes().toRequestBody(contentType.toMediaTypeOrNull())
        val request = Request.Builder()
            .url(httpUrl)
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw IOException("PUT failed: ${response.code} for $httpUrl")
            }
        }
        Log.d(tag, "Uploaded $contentLength bytes to $httpUrl")
    }

    /** Creates a collection (directory) at this path. */
    @Throws(IOException::class)
    fun mkdir() {
        val request = Request.Builder()
            .url(httpUrl)
            .method("MKCOL", null)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 201) {
                throw IOException("MKCOL failed: ${response.code} for $httpUrl")
            }
        }
    }

    /** Deletes the file or (empty or not) collection at this path. */
    @Throws(IOException::class)
    fun delete() {
        val request = Request.Builder()
            .url(httpUrl)
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 204) {
                throw IOException("DELETE failed: ${response.code} for $httpUrl")
            }
        }
    }

    /** Moves/renames this resource to [destinationWebdavUri]. */
    @Throws(IOException::class)
    fun moveTo(destinationWebdavUri: String) {
        val destUrl = WebdavContexts.toHttpUrl(destinationWebdavUri)
        val request = Request.Builder()
            .url(httpUrl)
            .method("MOVE", null)
            .header("Destination", destUrl)
            .header("Overwrite", "F")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw IOException("MOVE failed: ${response.code} for $httpUrl")
            }
        }
    }

    /** Returns true if the remote resource exists. */
    fun exists(): Boolean {
        return try {
            val request = Request.Builder()
                .url(httpUrl)
                .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaTypeOrNull()))
                .header("Depth", "0")
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.d(tag, "exists() check failed for $httpUrl", e)
            false
        }
    }
}
