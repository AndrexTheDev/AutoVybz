package com.andrexthedev.autovybz.audit

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** File access for the static audits. Gradle runs unit tests with the app module as working directory. */
object ProjectFiles {
    val module: File by lazy {
        listOf(File("."), File("app")).map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").exists() }
            ?: error("app module not found from ${File(".").absolutePath}")
    }
    val root: File get() = module.parentFile

    fun module(path: String) = File(module, path)
    fun root(path: String) = File(root, path)

    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    fun xml(file: File): Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

    fun Element.children(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    fun Element.android(attr: String): String? = getAttributeNS(ANDROID_NS, attr).ifEmpty { null }

    /** Raw (still Android-escaped) text of every `<string>` in a resource file, in file order. */
    fun strings(file: File): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        xml(file).documentElement.children("string").forEach { out[it.getAttribute("name")] = it.textContent }
        return out
    }

    /** All AutoVybz Kotlin sources (main). */
    fun autoVybzSources(): List<File> =
        module("src/main/java/com/andrexthedev").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
