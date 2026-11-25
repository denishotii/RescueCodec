// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.voice

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Installs a Vosk model from a zip file the person picked (vosk-model-small-de-0.15.zip). Works on
 * any phone with no computer: copy the zip to the phone, tap Import in Settings, pick it.
 * The model is unpacked into the app's private folder, so it needs no storage permission.
 */
object ModelImporter {
    sealed interface Result {
        data class Ok(val dir: File) : Result
        data class Failed(val reason: String) : Result
    }

    /** Unpacks [uri] for [lang] ("de" or "en"). Blocking: call it off the main thread. */
    fun import(context: Context, uri: Uri, lang: String): Result {
        val target = VoskModels.internalDir(context, lang)
        val tmp = File(target.parentFile, "$lang.import")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return Result.Failed("Cannot open the file.")
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                var count = 0
                while (entry != null) {
                    val out = File(tmp, entry.name)
                    // Zip-slip guard: nothing may land outside the temp folder.
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) return Result.Failed("The zip file has unsafe paths.")
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        count++
                    }
                    entry = zip.nextEntry
                }
                if (count == 0) return Result.Failed("The zip file is empty.")
            }
            val root = VoskModels.modelRoot(tmp) ?: return Result.Failed("This zip is not a Vosk model (no am/final.mdl).")
            target.deleteRecursively()
            if (!root.renameTo(target)) return Result.Failed("Could not move the model into place.")
            tmp.deleteRecursively()
            return Result.Ok(target)
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }
}
