// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.voice

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/** Where the Vosk speech model of one language is on this phone. */
sealed interface ModelStatus {
    /** Unpacked and ready: [dir] holds `am/`, `conf/`, `graph/`. */
    data class Ready(val dir: File) : ModelStatus

    /** Inside the APK (full build), not unpacked yet. The first use unpacks it. */
    data object Bundled : ModelStatus

    /** Not on the phone. [hint] is the folder to copy the model to (scripts/install-vosk-models.sh does it). */
    data class Missing(val hint: File) : ModelStatus
}

/**
 * Finds the Vosk models. Nothing is downloaded: the phone has no internet in the field.
 *
 * Packaging (see docs/VOICE.md): the default APK is slim and has no models. The models come from
 * `scripts/install-vosk-models.sh` (adb push into the app's own folder), or the full APK is built
 * with `-PbundleVosk=true` and carries both small models in its assets.
 *
 * Search order for language `de` or `en`:
 * 1. `files/vosk/<lang>` inside the app (unpacked from the APK assets)
 * 2. `Android/data/org.rescuecodec.app/files/vosk/<lang>` on shared storage (adb push)
 */
object VoskModels {
    const val ASSET_ROOT = "vosk"
    private const val MARKER = ".unpacked"

    fun internalDir(context: Context, lang: String) = File(File(context.filesDir, ASSET_ROOT), lang)

    fun externalDir(context: Context, lang: String): File? =
        context.getExternalFilesDir(null)?.let { File(File(it, ASSET_ROOT), lang) }

    /** A folder is a model when it has the acoustic model; a model may sit one level down (unzipped as is). */
    fun modelRoot(dir: File?): File? {
        if (dir == null || !dir.isDirectory) return null
        if (File(dir, "am/final.mdl").isFile) return dir
        return dir.listFiles()?.firstOrNull { it.isDirectory && File(it, "am/final.mdl").isFile }
    }

    fun status(context: Context, lang: String): ModelStatus {
        modelRoot(internalDir(context, lang))?.let { return ModelStatus.Ready(it) }
        modelRoot(externalDir(context, lang))?.let { return ModelStatus.Ready(it) }
        if (hasAssets(context.assets, lang)) return ModelStatus.Bundled
        return ModelStatus.Missing(externalDir(context, lang) ?: internalDir(context, lang))
    }

    private fun hasAssets(am: AssetManager, lang: String): Boolean =
        runCatching { am.list("$ASSET_ROOT/$lang")?.isNotEmpty() == true }.getOrDefault(false)

    /** Returns the model folder, unpacking it from the APK first when needed. Null if there is none. Blocking. */
    fun ensure(context: Context, lang: String): File? {
        when (val s = status(context, lang)) {
            is ModelStatus.Ready -> return s.dir
            is ModelStatus.Missing -> return null
            ModelStatus.Bundled -> Unit
        }
        val target = internalDir(context, lang)
        val tmp = File(target.parentFile, "$lang.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        copyAssets(context.assets, "$ASSET_ROOT/$lang", tmp)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) return null
        File(target, MARKER).writeText("ok")
        return modelRoot(target)
    }

    private fun copyAssets(am: AssetManager, path: String, to: File) {
        val children = am.list(path).orEmpty()
        if (children.isEmpty()) {
            am.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        for (c in children) copyAssets(am, "$path/$c", File(to, c))
    }
}
