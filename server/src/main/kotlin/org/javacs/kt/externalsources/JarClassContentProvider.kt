package org.javacs.kt.externalsources

import org.javacs.kt.CompilerClassPath
import org.javacs.kt.ExternalSourcesConfiguration
import org.javacs.kt.LOG
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.j2k.convertJavaToKotlin
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.LinkedHashMap

/**
 * Provides the source code for classes located inside
 * compiled or source JARs.
 */
class JarClassContentProvider(
    private val config: ExternalSourcesConfiguration,
    private val cp: CompilerClassPath,
    private val tempDir: TemporaryDirectory,
    private val decompiler: Decompiler = FernflowerDecompiler()
) {
    /** Maps recently used (source-)KLS-URIs to their source contents (e.g. decompiled code) and the file extension. */
    private val cachedContents = object : LinkedHashMap<String, Pair<String, String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>) = size > 5
    }

    /**
     * Fetches the contents of a compiled class/source file in a JAR
     * and another URI which can be used to refer to these extracted
     * contents.
     * If the file is inside a source JAR, the source code is returned as is.
     */
    public fun contentOf(uri: KlsURI, source: Boolean): Pair<KlsURI, String> {
        val key = uri.toString()
        val (contents, extension) = cachedContents[key] ?: run {
                LOG.info("Finding contents of {}", describeURI(uri.uri))
                tryReadContentOf(uri, source)
                    ?: tryReadContentOf(uri.withFileExtension("class"), source)
                    ?: tryReadContentOf(uri.withFileExtension("java"), source)
                    ?: tryReadContentOf(uri.withFileExtension("kt"), source)
                    ?: throw KotlinLSException("Could not find $uri")
            }.also {
                cachedContents[key] = Pair(it.first, it.second)
            }
        val sourceURI = uri.withFileExtension(extension).withSource(source)
        return Pair(sourceURI, contents)
    }

    private fun convertToKotlinIfNeeded(javaCode: String): String = if (config.autoConvertToKotlin) {
        convertJavaToKotlin(javaCode, cp.compiler)
    } else {
        javaCode
    }

    private fun tryReadContentOf(uri: KlsURI, source: Boolean): Pair<String, String>? = try {
        val actualUri = uri.withoutQuery()
        when (actualUri.fileExtension) {
            "class" -> Pair(actualUri.extractToTemporaryFile(tempDir)
                .let(decompiler::decompileClass)
                .let { Files.newInputStream(it) }
                .bufferedReader()
                .use(BufferedReader::readText)
                .let(this::convertToKotlinIfNeeded), if (config.autoConvertToKotlin) "kt" else "java")
            "java" -> if (source) Pair(actualUri.readContents(), "java") else Pair(convertToKotlinIfNeeded(actualUri.readContents()), "kt")
            else -> Pair(actualUri.readContents(), "kt") // e.g. for Kotlin source files
        }
    } catch (e: FileNotFoundException) { null }
}
