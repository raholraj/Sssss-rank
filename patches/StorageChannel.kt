package com.powerfiles.channels

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File

class StorageChannel(private val context: Context, messenger: BinaryMessenger) {
    init {
        MethodChannel(messenger, "com.powerfiles/storage").setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "getStorageStats" -> result.success(getStorageStats())
                    "getCategoryBreakdown" -> result.success(getCategoryBreakdown())
                    "getAppStorageList" -> result.success(getAppStorageList())
                    "getMediaByFolder" -> {
                        val type = call.argument<String>("type") ?: "images"
                        result.success(getMediaByFolder(type))
                    }
                    "getDuplicateCandidates" -> {
                        val path = call.argument<String>("path") ?: Environment.getExternalStorageDirectory().absolutePath
                        result.success(findDuplicateCandidates(path))
                    }
                    "getJunkFiles" -> {
                        val path = call.argument<String>("path") ?: Environment.getExternalStorageDirectory().absolutePath
                        result.success(findJunkFiles(path))
                    }
                    "getLargeFiles" -> {
                        val path = call.argument<String>("path") ?: Environment.getExternalStorageDirectory().absolutePath
                        val minSize = call.argument<Long>("minSize") ?: 10 * 1024 * 1024L
                        result.success(findLargeFiles(path, minSize))
                    }
                    "getSDCardPath" -> result.success(getSDCardPath())
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("STORAGE_ERROR", e.message, null)
            }
        }
    }

    private fun getStorageStats(): Map<String, Any> {
        val statFs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val totalBytes = statFs.totalBytes
        val freeBytes = statFs.availableBytes
        val usedBytes = totalBytes - freeBytes
        return mapOf(
            "totalBytes" to totalBytes,
            "usedBytes" to usedBytes,
            "freeBytes" to freeBytes,
            "percentUsed" to (usedBytes * 100 / totalBytes).toInt()
        )
    }

    private fun getCategoryBreakdown(): Map<String, Long> {
        val result = mutableMapOf(
            "images" to 0L, "videos" to 0L, "audio" to 0L,
            "documents" to 0L, "apks" to 0L, "archives" to 0L, "other" to 0L
        )
        result["images"] = getMediaSize(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        result["videos"] = getMediaSize(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        result["audio"] = getMediaSize(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        val root = Environment.getExternalStorageDirectory()
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val ext = file.extension.lowercase()
            when {
                ext in listOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","odt") ->
                    result["documents"] = result["documents"]!! + file.length()
                ext == "apk" -> result["apks"] = result["apks"]!! + file.length()
                ext in listOf("zip","rar","tar","gz","7z","bz2","xz") ->
                    result["archives"] = result["archives"]!! + file.length()
            }
        }
        return result
    }

    private fun getMediaSize(uri: Uri): Long {
        var size = 0L
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) size += cursor.getLong(sizeColumn)
        }
        return size
    }

    private fun getAppStorageList(): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in apps.take(50)) {
            try {
                val stats = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                    val uuid = StorageManager.UUID_DEFAULT
                    ssm.queryStatsForPackage(uuid, app.packageName, android.os.Process.myUserHandle())
                } else null
                results.add(mapOf(
                    "packageName" to app.packageName,
                    "name" to (pm.getApplicationLabel(app)?.toString() ?: app.packageName),
                    "size" to (stats?.dataBytes ?: 0L)
                ))
            } catch (_: Exception) {}
        }
        return results.sortedByDescending { it["size"] as Long }
    }

    private fun getMediaByFolder(type: String): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        val root = Environment.getExternalStorageDirectory()
        val folders = when (type) {
            "images" -> listOf("DCIM", "Pictures")
            "videos" -> listOf("DCIM", "Movies")
            "audio" -> listOf("Music", "Podcasts", "Audiobooks")
            else -> listOf("Download")
        }
        for (folder in folders) {
            val dir = File(root, folder)
            if (dir.exists()) {
                results.add(mapOf(
                    "name" to folder,
                    "path" to dir.absolutePath,
                    "count" to (dir.listFiles()?.size ?: 0)
                ))
            }
        }
        return results
    }

    private fun findDuplicateCandidates(path: String): List<Map<String, Any>> {
        val sizeMap = mutableMapOf<Long, MutableList<String>>()
        File(path).walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .forEach { file ->
                sizeMap.getOrPut(file.length()) { mutableListOf() }.add(file.absolutePath)
            }
        return sizeMap
            .filter { it.value.size > 1 }
            .map { (size, paths) ->
                mapOf("size" to size, "paths" to paths, "wastedBytes" to (size * (paths.size - 1)))
            }
            .sortedByDescending { it["wastedBytes"] as Long }
            .take(50).toList()
    }

    private fun findJunkFiles(path: String): List<Map<String, Any>> {
        val junkExtensions = setOf("tmp", "temp", "bak", "old", "cache", "log", "hprof", "obb")
        val junkNames = setOf("Thumbs.db", ".DS_Store", "desktop.ini", ".nomedia")
        val results = mutableListOf<Map<String, Any>>()
        File(path).walkTopDown().forEach { file ->
            val isJunk = when {
                file.isFile && file.length() == 0L -> true
                file.isDirectory && (file.listFiles()?.isEmpty() == true) -> true
                file.isFile && file.extension.lowercase() in junkExtensions -> true
                file.isFile && file.name in junkNames -> true
                else -> false
            }
            if (isJunk) {
                results.add(mapOf(
                    "path" to file.absolutePath,
                    "name" to file.name,
                    "size" to file.length(),
                    "isDirectory" to file.isDirectory,
                    "reason" to getJunkReason(file)
                ))
            }
        }
        return results
    }

    private fun getJunkReason(file: File): String = when {
        file.length() == 0L -> "Empty file"
        file.isDirectory -> "Empty folder"
        file.extension.lowercase() in setOf("tmp", "temp") -> "Temporary file"
        file.extension.lowercase() in setOf("bak", "old") -> "Backup file"
        file.extension.lowercase() == "log" -> "Log file"
        file.extension.lowercase() == "cache" -> "Cache file"
        else -> "Junk file"
    }

    private fun findLargeFiles(path: String, minSize: Long): List<Map<String, Any>> {
        return File(path).walkTopDown()
            .filter { it.isFile && it.length() >= minSize }
            .sortedByDescending { it.length() }
            .take(100)
            .map { file ->
                mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "size" to file.length(),
                    "lastModified" to file.lastModified(),
                    "extension" to file.extension
                )
            }.toList()
    }

    private fun getSDCardPath(): String? {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return storageManager.storageVolumes
            .firstOrNull { !it.isPrimary && it.isRemovable }?.directory?.absolutePath
    }
}
