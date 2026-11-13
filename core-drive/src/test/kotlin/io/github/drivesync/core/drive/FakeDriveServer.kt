package io.github.drivesync.core.drive

import io.github.drivesync.core.drive.api.DriveApi
import io.github.drivesync.core.drive.api.DriveClient
import io.github.drivesync.core.drive.api.DriveFile
import io.github.drivesync.core.drive.api.DriveFileList
import io.github.drivesync.core.drive.api.DriveFileMetadata
import io.github.drivesync.core.drive.auth.AuthProvider
import io.github.drivesync.core.drive.http.BackoffPolicy
import io.github.drivesync.core.drive.http.DriveHttp
import io.github.drivesync.core.drive.http.RetryInterceptor
import io.github.drivesync.core.sync.fakes.md5Hex
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A small in-memory Drive v3 behind MockWebServer, so tests run the real Retrofit/OkHttp stack. It implements only
 * what the app calls: files.list (with the app's `q` grammar), get, create, update, media download and the
 * resumable upload protocol. Trashing a folder hides everything below it, like Drive.
 */
class FakeDriveServer :
    Dispatcher(),
    AutoCloseable {
    class Node(
        val id: String,
        var name: String,
        val mimeType: String,
        var parents: MutableList<String>,
        var content: ByteArray = ByteArray(0),
        var trashed: Boolean = false,
        val appProperties: Map<String, String> = emptyMap(),
        var version: Long = 1,
        var modifiedTime: Long = 0,
        val createdTime: Long = 0,
    ) {
        val isFolder get() = mimeType == DriveFile.FOLDER_MIME_TYPE
    }

    private class Session(
        val replaces: String?,
        val metadata: DriveFileMetadata,
        var bytes: ByteArray = ByteArray(0),
        var canceled: Boolean = false,
    )

    private val server = MockWebServer()
    private val nodes = linkedMapOf<String, Node>()
    private val sessions = mutableMapOf<String, Session>()
    private val faults = mutableListOf<Pair<(RecordedRequest) -> Boolean, MockResponse>>()
    private var nextId = 1
    private var clock = 1_700_000_000_000L
    private val json = DriveHttp.json

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Max files per `files.list` page, to exercise pagination. */
    var pageLimit = DriveApi.MAX_PAGE_SIZE

    /** When set, each upload chunk stores at most this many bytes (Drive may keep less than it was sent). */
    var chunkAcceptLimit: Int? = null

    /** When set, only this bearer token is accepted; others get `401`. */
    var acceptedToken: String? = null

    init {
        server.dispatcher = this
        server.start()
        nodes[MY_DRIVE] = Node(MY_DRIVE, "My Drive", DriveFile.FOLDER_MIME_TYPE, mutableListOf())
    }

    val baseUrl: String get() = server.url("/").toString()

    /** A [DriveClient] wired through the production HTTP stack, with instant backoff. */
    fun client(
        auth: AuthProvider = StaticAuth(),
        chunkSize: Int = 8,
    ): DriveClient {
        val backoff = BackoffPolicy(maxRetries = 3, baseDelayMillis = 0)
        return DriveClient(DriveHttp.api(auth, baseUrl, RetryInterceptor(backoff) {}), chunkSize, backoff)
    }

    override fun close() = server.close()

    // ---- Test-side helpers (they bypass the HTTP API, like an edit on another device or the website) ----

    fun folder(
        name: String,
        parent: String = MY_DRIVE,
        appProperties: Map<String, String> = emptyMap(),
    ): String = add(name, DriveFile.FOLDER_MIME_TYPE, parent, ByteArray(0), appProperties).id

    fun file(
        name: String,
        parent: String,
        content: String = name,
        mimeType: String = "text/plain",
    ): String = add(name, mimeType, parent, content.toByteArray(), emptyMap()).id

    fun node(id: String): Node = synchronized(this) { nodes.getValue(id) }

    fun exists(id: String) = synchronized(this) { id in nodes }

    fun edit(
        id: String,
        content: String,
    ) = synchronized(this) {
        val n = nodes.getValue(id)
        n.content = content.toByteArray()
        n.version++
        n.modifiedTime = tick()
    }

    fun trash(id: String) = synchronized(this) { nodes.getValue(id).trashed = true }

    fun delete(id: String) = synchronized(this) { nodes.remove(id) }

    fun reparent(
        id: String,
        parent: String,
    ) = synchronized(this) { nodes.getValue(id).parents = mutableListOf(parent) }

    /** Live files below [folderId] as path → content. */
    fun tree(folderId: String): Map<String, String> =
        synchronized(this) {
            val out = sortedMapOf<String, String>()

            fun walk(
                id: String,
                prefix: String,
            ) {
                nodes.values.filter { id in it.parents && !it.trashed }.forEach {
                    val path = prefix + it.name
                    if (it.isFolder) walk(it.id, "$path/") else out[path] = it.content.decodeToString()
                }
            }
            walk(folderId, "")
            out
        }

    fun children(folderId: String): List<Node> = synchronized(this) { nodes.values.filter { folderId in it.parents } }

    /** The next request matching [match] gets [response] instead of the normal answer. */
    fun failNext(
        response: MockResponse,
        times: Int = 1,
        match: (RecordedRequest) -> Boolean = { true },
    ) = synchronized(this) { repeat(times) { faults += match to response } }

    fun requestCount(
        method: String,
        pathPrefix: String,
    ): Int = requests.count { it.method == method && it.url.encodedPath.startsWith(pathPrefix) }

    // ---- HTTP ----

    override fun dispatch(request: RecordedRequest): MockResponse =
        synchronized(this) {
            requests += request
            val fault = faults.firstOrNull { it.first(request) }
            if (fault != null) {
                faults.remove(fault)
                return fault.second
            }
            val token = request.headers["Authorization"]?.removePrefix("Bearer ")
            if (token.isNullOrEmpty() || (acceptedToken != null && token != acceptedToken)) {
                return error(HTTP_UNAUTHORIZED, "authError")
            }
            route(request)
        }

    @Suppress("CyclomaticComplexMethod") // A flat routing table.
    private fun route(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val id = path.substringAfterLast('/')
        return when {
            request.method == "GET" && path == "/drive/v3/files" -> list(request)
            request.method == "GET" && path.startsWith("/drive/v3/files/") -> get(id, request)
            request.method == "POST" && path == "/drive/v3/files" -> create(request.metadata())
            request.method == "PATCH" && path.startsWith("/drive/v3/files/") -> update(id, request)
            request.method == "POST" && path == "/upload/drive/v3/files" -> startSession(null, request)
            request.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") -> startSession(id, request)
            request.method == "PUT" && path.startsWith(SESSION_PATH) -> chunk(id, request)
            request.method == "DELETE" && path.startsWith(SESSION_PATH) -> cancel(id)
            else -> error(HTTP_BAD_REQUEST, "unsupported ${request.method} $path")
        }
    }

    private fun list(request: RecordedRequest): MockResponse {
        val q = request.url.queryParameter("q").orEmpty()
        val all = nodes.values.filter { it.id != MY_DRIVE && matches(it, q) }.sortedBy { it.id }
        val start = request.url.queryParameter("pageToken")?.toInt() ?: 0
        val page = all.drop(start).take(pageLimit)
        val next = (start + pageLimit).takeIf { it < all.size }?.toString()
        return ok(json.encodeToString(DriveFileList(page.map { it.toDto() }, next)))
    }

    private fun get(
        id: String,
        request: RecordedRequest,
    ): MockResponse {
        val node = nodes[id] ?: return error(HTTP_NOT_FOUND, "notFound")
        if (request.url.queryParameter("alt") == "media") {
            return MockResponse.Builder().body(okio.Buffer().write(node.content)).build()
        }
        return ok(node.toDto())
    }

    private fun create(metadata: DriveFileMetadata): MockResponse {
        val parent = metadata.parents?.single() ?: MY_DRIVE
        if (parent !in nodes) return error(HTTP_NOT_FOUND, "notFound")
        val node =
            add(
                metadata.name.orEmpty(),
                metadata.mimeType ?: DriveFile.FOLDER_MIME_TYPE,
                parent,
                ByteArray(0),
                metadata.appProperties.orEmpty(),
            )
        return ok(node.toDto())
    }

    private fun update(
        id: String,
        request: RecordedRequest,
    ): MockResponse {
        val node = nodes[id] ?: return error(HTTP_NOT_FOUND, "notFound")
        val metadata = request.metadata()
        metadata.name?.let { node.name = it }
        metadata.trashed?.let { node.trashed = it }
        request.url
            .queryParameter("removeParents")
            ?.split(',')
            ?.let { node.parents.removeAll(it) }
        request.url.queryParameter("addParents")?.let { node.parents.add(it) }
        node.version++
        return ok(node.toDto())
    }

    private fun startSession(
        replaces: String?,
        request: RecordedRequest,
    ): MockResponse {
        if (replaces != null && replaces !in nodes) return error(HTTP_NOT_FOUND, "notFound")
        val sessionId = "s${sessions.size + 1}"
        sessions[sessionId] = Session(replaces, request.metadata())
        return MockResponse.Builder().addHeader("Location", server.url(SESSION_PATH + sessionId)).build()
    }

    private fun chunk(
        sessionId: String,
        request: RecordedRequest,
    ): MockResponse {
        val session = sessions[sessionId]?.takeUnless { it.canceled } ?: return error(HTTP_NOT_FOUND, "notFound")
        val range = request.headers["Content-Range"] ?: return error(HTTP_BAD_REQUEST, "no Content-Range")
        val match = CONTENT_RANGE.matchEntire(range) ?: return error(HTTP_BAD_REQUEST, "bad Content-Range $range")
        val (from, _, total) = match.destructured
        val body = request.body?.toByteArray() ?: ByteArray(0)
        if (from.isNotEmpty()) {
            if (from.toLong() != session.bytes.size.toLong()) return error(HTTP_BAD_REQUEST, "gap or overlap at $from")
            val kept = chunkAcceptLimit?.let { body.copyOf(minOf(it, body.size)) } ?: body
            session.bytes += kept
        }
        if (total != "*" && session.bytes.size.toLong() == total.toLong()) return finish(session)
        val received = session.bytes.size
        return MockResponse
            .Builder()
            .code(HTTP_RESUME_INCOMPLETE)
            .apply { if (received > 0) addHeader("Range", "bytes=0-${received - 1}") }
            .build()
    }

    private fun finish(session: Session): MockResponse {
        sessions.values.remove(session)
        val modified = session.metadata.modifiedTime?.let { Instant.parse(it).toEpochMilli() } ?: tick()
        val node =
            if (session.replaces != null) {
                nodes.getValue(session.replaces).apply {
                    content = session.bytes
                    version++
                }
            } else {
                add(
                    session.metadata.name.orEmpty(),
                    session.metadata.mimeType ?: "application/octet-stream",
                    session.metadata.parents?.single() ?: MY_DRIVE,
                    session.bytes,
                    emptyMap(),
                )
            }
        node.modifiedTime = modified
        return ok(node.toDto())
    }

    private fun cancel(sessionId: String): MockResponse {
        sessions[sessionId]?.canceled = true
        return MockResponse.Builder().code(HTTP_NO_CONTENT).build()
    }

    private fun matches(
        node: Node,
        q: String,
    ): Boolean {
        val parentIds = PARENT.findAll(q).map { it.groupValues[1] }.toList()
        val name =
            NAME
                .find(q)
                ?.groupValues
                ?.get(1)
                ?.replace("\\'", "'")
                ?.replace("\\\\", "\\")
        val mime = MIME.find(q)?.groupValues?.get(1)
        val property = PROPERTY.find(q)?.destructured
        return (parentIds.isEmpty() || node.parents.any { it in parentIds }) &&
            ("trashed = false" !in q || !isTrashed(node)) &&
            (name == null || node.name == name) &&
            (mime == null || node.mimeType == mime) &&
            (property == null || node.appProperties[property.component1()] == property.component2())
    }

    private fun isTrashed(node: Node): Boolean =
        node.trashed || node.parents.any { parent -> nodes[parent]?.let(::isTrashed) == true }

    private fun add(
        name: String,
        mimeType: String,
        parent: String,
        content: ByteArray,
        appProperties: Map<String, String>,
    ): Node =
        synchronized(this) {
            val now = tick()
            val node =
                Node(
                    id = "id%04d".format(nextId++),
                    name = name,
                    mimeType = mimeType,
                    parents = mutableListOf(parent),
                    content = content,
                    appProperties = appProperties,
                    modifiedTime = now,
                    createdTime = now,
                )
            nodes[node.id] = node
            node
        }

    private fun tick(): Long {
        clock += 1000
        return clock
    }

    private fun Node.toDto() =
        DriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            size = if (isFolder) null else content.size.toString(),
            md5Checksum = if (isFolder || mimeType.startsWith(GOOGLE_APPS)) null else md5Hex(content),
            version = version.toString(),
            modifiedTime = Instant.ofEpochMilli(modifiedTime).toString(),
            createdTime = Instant.ofEpochMilli(createdTime).toString(),
            parents = parents.toList(),
            trashed = isTrashed(this),
            appProperties = appProperties,
        )

    private fun RecordedRequest.metadata(): DriveFileMetadata =
        body?.utf8()?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(it) } ?: DriveFileMetadata()

    private fun ok(dto: DriveFile) = ok(json.encodeToString(dto))

    private fun ok(body: String) =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    companion object {
        const val MY_DRIVE = "root"
        const val GOOGLE_APPS = "application/vnd.google-apps."
        private const val SESSION_PATH = "/upload/session/"
        private const val HTTP_RESUME_INCOMPLETE = 308
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private val PARENT = Regex("'([^']*)' in parents")
        private val NAME = Regex("""name = '((?:[^'\\]|\\.)*)'""")
        private val MIME = Regex("mimeType = '([^']*)'")
        private val PROPERTY = Regex("""appProperties has \{ key='([^']*)' and value='([^']*)' \}""")
        private val CONTENT_RANGE = Regex("""bytes (?:(\d+)-(\d+)|\*)/(\d+|\*)""")

        fun error(
            code: Int,
            reason: String,
        ): MockResponse =
            MockResponse
                .Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"code":$code,"message":"$reason","errors":[{"reason":"$reason"}]}}""")
                .build()
    }
}

/** Hands out a fixed token; records invalidations. */
class StaticAuth(
    private val tokens: ArrayDeque<String> = ArrayDeque(listOf("token-1")),
) : AuthProvider {
    val invalidated = mutableListOf<String>()

    override suspend fun accessToken(): String = tokens.first()

    override suspend fun invalidate(token: String) {
        invalidated += token
        if (tokens.size > 1) tokens.removeFirst()
    }
}
