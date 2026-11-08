package io.github.ddastoor.kindlify.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps each canvas in app-private storage:
 *
 *     files/projects/<id>/project.json
 *     files/projects/<id>/images/<image files>
 *     files/projects/<id>/epub/<Title>.epub
 *
 * Every change is written straight away, so nothing is lost if Android
 * closes the app while you are copying things in another app.
 */
class ProjectStore(filesDir: File) {

    private val root = File(filesDir, "projects")

    fun projectDir(id: String) = File(root, id)
    fun imagesDir(id: String) = File(projectDir(id), "images")
    fun epubDir(id: String) = File(projectDir(id), "epub")
    fun imageFile(projectId: String, image: Block.Image) = File(imagesDir(projectId), image.fileName)

    fun epubFile(project: Project): File? =
        project.epubFileName?.let { File(epubDir(project.id), it) }?.takeIf { it.isFile }

    fun list(): List<Project> =
        root.listFiles().orEmpty()
            .mapNotNull { dir -> runCatching { load(dir.name) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Project? {
        val file = File(projectDir(id), "project.json")
        if (!file.isFile) return null
        return fromJson(JSONObject(file.readText()))
    }

    fun save(project: Project) {
        val dir = projectDir(project.id).apply { mkdirs() }
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(toJson(project).toString())
        check(tmp.renameTo(File(dir, "project.json"))) { "Could not save canvas" }
    }

    fun delete(id: String) {
        projectDir(id).deleteRecursively()
    }

    private fun toJson(p: Project) = JSONObject().apply {
        put("id", p.id)
        put("title", p.title)
        put("createdAt", p.createdAt)
        put("updatedAt", p.updatedAt)
        p.epubFileName?.let { put("epubFileName", it) }
        put("epubBuiltFrom", p.epubBuiltFrom)
        put("blocks", JSONArray().apply {
            p.blocks.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id)
                    when (b) {
                        is Block.Text -> {
                            put("type", "text")
                            put("text", b.text)
                        }
                        is Block.Image -> {
                            put("type", "image")
                            put("file", b.fileName)
                            put("width", b.width)
                            put("height", b.height)
                        }
                    }
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): Project {
        val arr = o.getJSONArray("blocks")
        val blocks = (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            when (b.getString("type")) {
                "image" -> Block.Image(b.getString("id"), b.getString("file"), b.getInt("width"), b.getInt("height"))
                else -> Block.Text(b.getString("id"), b.getString("text"))
            }
        }
        return Project(
            id = o.getString("id"),
            title = o.getString("title"),
            createdAt = o.getLong("createdAt"),
            updatedAt = o.getLong("updatedAt"),
            blocks = blocks,
            epubFileName = if (o.has("epubFileName")) o.getString("epubFileName") else null,
            epubBuiltFrom = o.optLong("epubBuiltFrom", 0),
        )
    }
}
