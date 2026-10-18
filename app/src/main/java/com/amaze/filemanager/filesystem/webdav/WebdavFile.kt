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
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.GetContentLength
import at.bitfire.dav4jvm.property.GetContentType
import at.bitfire.dav4jvm.property.GetLastModified
import at.bitfire.dav4jvm.property.ResourceType
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin wrapper around dav4jvm's [DavResource] that exposes the file
 * operations Amaze's [com.amaze.filemanager.filesystem.HybridFile]
 * needs: list, stat, download, upload, mkdir, delete, move.
 */
class WebdavFile(
    webdavUri: String,
    username: String?,
    password: String?,
) {
    private val tag = WebdavFile::class.java.simpleName

    private val httpUrl = WebdavContexts.toHttpUrl(webdavUri).toHttpUrl()
    private val client = WebdavContexts.getClient(webdavUri, username, password)
    private val resource = DavResource(client, httpUrl)

    /** Simple stat info for one remote entry. */
    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        /** webdav:// URI of this entry */
        val uri: String,
    )

    /**
     * Lists the direct children of this collection (depth 1).
     * Returns empty list on error; check logs for details.
     */
    @Throws(IOException::class)
    fun list(): List<Entry> {
        val responses = mutableListOf<Response>()
        resource.propfind(
            depth = 1,
            callback = { response, _ -> responses.add(response) },
            DisplayName.NAME,
            GetContentLength.NAME,
            GetContentType.NAME,
            GetLastModified.NAME,
            ResourceType.NAME,
        )
        // First response is the collection itself; skip it.
        return responses.drop(1).mapNotNull { response ->
            try {
                val href = response.href.toString()
                val name = href.substringAfterLast("/").ifEmpty {
                    // Collection href ends with "/"
                    href.trimEnd('/').substringAfterLast("/")
                }
                if (name.isEmpty()) return@mapNotNull null
                val resourceType = response[ResourceType::class.java]
                val isDir = resourceType?.types?.any { it.name == "collection" } == true
                val size = response[GetContentLength::class.java]?.contentLength ?: 0L
                val lastModified =
                    response[GetLastModified::class.java]?.lastModified?.toEpochSecond()
                        ?.times(1000) ?: 0L
                val childHttpUrl = httpUrl.toString().trimEnd('/') + "/" + name
                Entry(
                    name = name,
                    isDirectory = isDir,
                    size = size,
                    lastModified = lastModified,
                    uri = WebdavContexts.toWebdavUri(childHttpUrl),
                )
            } catch (e: Exception) {
                Log.w(tag, "Skipping malformed PROPFIND entry", e)
                null
            }
        }
    }

    /** Downloads the remote file into [output]. */
    @Throws(IOException::class)
    fun download(output: OutputStream) {
        resource.get("*/*") { response ->
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
        val body =
            input.readBytes().toRequestBody(contentType.toMediaTypeOrNull())
        // dav4jvm put() is synchronous when no callback threading is involved
        resource.put(body)
        Log.d(tag, "Uploaded $contentLength bytes to $httpUrl")
    }

    /** Creates a collection (directory) at this path. */
    @Throws(IOException::class)
    fun mkdir() {
        resource.mkCol(null, null)
    }

    /** Deletes the file or (empty or not) collection at this path. */
    @Throws(IOException::class)
    fun delete() {
        resource.delete()
    }

    /** Moves/renames this resource to [destinationWebdavUri]. */
    @Throws(IOException::class)
    fun moveTo(destinationWebdavUri: String) {
        val destUrl = WebdavContexts.toHttpUrl(destinationWebdavUri).toHttpUrl()
        val dest = DavResource(client, destUrl)
        resource.move(dest, overwrite = false)
    }

    /** Returns true if the remote resource exists. */
    fun exists(): Boolean {
        return try {
            var found = false
            resource.propfind(
                depth = 0,
                callback = { _, _ -> found = true },
                DisplayName.NAME,
            )
            found
        } catch (e: Exception) {
            Log.d(tag, "exists() check failed for $httpUrl", e)
            false
        }
    }
}
