package com.powerfiles.channels

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.UUID

class StorageChannel(private val context: Context) : MethodChannel.MethodCallHandler {

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getStorageStats" -> {
                try {
                    result.success(getStorageStats())
                } catch (e: Exception) {
                    result.error("STORAGE_ERROR", e.message, null)
                }
            }
            "getAppStorageList" -> {
                try {
                    result.success(getAppStorageList())
                } catch (e: Exception) {
                    result.error("APP_STORAGE_ERROR", e.message, null)
                }
            }
            "getMediaByFolder" -> {
                val type = call.argument<String>("type") ?: "images"
                try {
                    result.success(getMediaByFolder(type))
                } catch (e: Exception) {
                    result.error("MEDIA_ERROR", e.message, null)
                }
            }
            "findDuplicateCandidates" -> {
                val path = call.argument<String>("path") ?: Environment.getExternalStorageDirectory().absolutePath
                try {
                    result.success(findDuplicateCandidates(path))
                } catch (e: Exception) {
                    result.error("DUP_ERROR", e.message, null)
                }
            }
            "findJunkFiles" -> {
                val path = call.argument<String>("path") ?: Environment.getExternalStorageDirectory().absolutePath
                try {
                    result.success(findJunkFiles(path))
                } catch (e: Exception) {
                    result.error("JUNK_ERROR", e.message, null)
                }
            }
            "findLargeFiles" -> {
                val path = call.argument<String>("path") ?: Environment.getExternalStorageDirectory().absolutePath
                val minSize = (call.argument<Number>("minSizeMB")?.toLong() ?: 10L) * 1024 * 1024
                try {
                    result.success(findLargeFiles(path, minSize))
                } catch (e: Exception) {
                    result.error("LARGE_ERROR", e.message, null)
                }
            }
            "getSDCardPath" -> {
                result.success(getSDCardPath())
            }
            else -> result.notImplemented()
        }
    }

    private fun getStorageStats(): Map<String, Any> {
        val path = Environment.getExternalStorageDirectory()
        val stat = StatFs(path.path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = total - free
        val percentUsed = if (total > 0) ((used * 100.0) / total).toInt() else 0
        return mapOf(
            "totalBytes" to total,
            "freeBytes" to free,
            "usedBytes" to used,
            "percentUsed" to percentUsed
        )
    }

    private fun getAppStorageList(): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        val dirs = listOf(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
            context.externalCacheDir
        )
        for (dir in dirs) {
            if (dir != null && dir.exists()) {
                results.add(mapOf(
                    "path" to dir.absolutePath,
                    "name" to dir.name,
                    "size" to dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
                ))
            }
        }
        return results
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
            .take(50)
            .toList()
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
        val volumes = storageManager.storageVolumes
        return volumes.firstOrNull { !it.isPrimary && it.isRemovable }?.directory?.absolutePath
    }
}
