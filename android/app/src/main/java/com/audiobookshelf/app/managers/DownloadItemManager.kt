package com.audiobookshelf.app.managers

import android.app.DownloadManager
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.callback.FileCallback
import com.anggrayudi.storage.file.DocumentFileCompat
import com.anggrayudi.storage.file.MimeType
import com.anggrayudi.storage.file.getAbsolutePath
import com.anggrayudi.storage.file.moveFileTo
import com.anggrayudi.storage.media.FileDescription
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DownloadItemManager(var downloadManager:DownloadManager, var folderScanner: FolderScanner, var mainActivity: MainActivity, var clientEventEmitter:DownloadEventEmitter) {
  val tag = "DownloadItemManager"
  private val maxSimultaneousDownloads = 5
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  enum class DownloadCheckStatus {
    InProgress,
    Successful,
    Failed
  }

  var downloadItemQueue: MutableList<DownloadItem> = mutableListOf()
  var currentDownloadItemParts: MutableList<DownloadItemPart> = mutableListOf()

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem:DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart:DownloadItemPart)
    fun onDownloadItemComplete(jsobj:JSObject)
  }

  companion object {
    var isDownloading:Boolean = false
  }

  fun addDownloadItem(downloadItem:DownloadItem) {
    DeviceManager.dbManager.saveDownloadItem(downloadItem)
    Log.i(tag, "Add download item ${downloadItem.media.metadata.title}")

    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    checkUpdateDownloadQueue()
  }

  private fun checkUpdateDownloadQueue() {
    for (downloadItem in downloadItemQueue) {
      val numPartsToGet = maxSimultaneousDownloads - currentDownloadItemParts.size
      val nextDownloadItemParts = downloadItem.getNextDownloadItemParts(numPartsToGet)
      Log.d(tag, "checkUpdateDownloadQueue: numPartsToGet=$numPartsToGet, nextDownloadItemParts=${nextDownloadItemParts.size}")

      if (nextDownloadItemParts.size > 0) {
        nextDownloadItemParts.forEach {
          val dlRequest = it.getDownloadRequest()
          val downloadId = downloadManager.enqueue(dlRequest)
          it.downloadId = downloadId
          Log.d(tag, "checkUpdateDownloadQueue: Starting download item part, downloadId=$downloadId")
          currentDownloadItemParts.add(it)
        }
      }

      if (currentDownloadItemParts.size >= maxSimultaneousDownloads) {
        break
      }
    }

    if (currentDownloadItemParts.size > 0) startWatchingDownloads()
  }

  private fun startWatchingDownloads() {
    if (isDownloading) return // Already watching

    GlobalScope.launch(Dispatchers.IO) {
      Log.d(tag, "Starting watching downloads")
      isDownloading = true

      while (currentDownloadItemParts.size > 0) {

        val itemParts = currentDownloadItemParts.filter { !it.isMoving }.map { it }
        for (downloadItemPart in itemParts) {
          val downloadCheckStatus = checkDownloadItemPart(downloadItemPart)
          clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

          // Will move to final destination, remove current item parts, and check if download item is finished
          handleDownloadItemPartCheck(downloadCheckStatus, downloadItemPart)
        }

        if (currentDownloadItemParts.size < maxSimultaneousDownloads) {
          checkUpdateDownloadQueue()
        }

        delay(500)
      }

      Log.d(tag, "Finished watching downloads")
      isDownloading = false
    }
  }

  private fun checkDownloadItemPart(downloadItemPart:DownloadItemPart):DownloadCheckStatus {
    val downloadId = downloadItemPart.downloadId ?: return DownloadCheckStatus.Failed

    val query = DownloadManager.Query().setFilterById(downloadId)
    downloadManager.query(query).use {
      if (it.moveToFirst()) {
        val bytesColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val statusColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val bytesDownloadedColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)

        val totalBytes = if (bytesColumnIndex >= 0) it.getInt(bytesColumnIndex) else 0
        val downloadStatus = if (statusColumnIndex >= 0) it.getInt(statusColumnIndex) else 0
        val bytesDownloadedSoFar = if (bytesDownloadedColumnIndex >= 0) it.getInt(bytesDownloadedColumnIndex) else 0
        Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} bytes $totalBytes | bytes dled $bytesDownloadedSoFar | downloadStatus $downloadStatus")

        if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
          Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Successful")
          downloadItemPart.completed = true
          return DownloadCheckStatus.Successful
        } else if (downloadStatus == DownloadManager.STATUS_FAILED) {
          Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Failed")
          downloadItemPart.completed = true
          downloadItemPart.failed = true
          return DownloadCheckStatus.Failed
        } else {
          //update progress
          val percentProgress = if (totalBytes > 0) ((bytesDownloadedSoFar * 100L) / totalBytes) else 0
          Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Progress = $percentProgress%")
          downloadItemPart.progress = percentProgress
          return DownloadCheckStatus.InProgress
        }
      } else {
        Log.d(tag, "Download ${downloadItemPart.filename} not found in dlmanager")
        downloadItemPart.completed = true
        downloadItemPart.failed = true
        return DownloadCheckStatus.Failed
      }
    }
  }

  private fun handleDownloadItemPartCheck(downloadCheckStatus:DownloadCheckStatus, downloadItemPart:DownloadItemPart) {
    val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
    if (downloadItem == null) {
      Log.e(tag, "Download item part finished but download item not found ${downloadItemPart.filename}")
      currentDownloadItemParts.remove(downloadItemPart)
    } else if (downloadCheckStatus == DownloadCheckStatus.Successful) {

      val file = DocumentFileCompat.fromUri(mainActivity, downloadItemPart.destinationUri)
      Log.d(tag, "DOWNLOAD: DESTINATION URI ${downloadItemPart.destinationUri}")

      val fcb = object : FileCallback() {
        override fun onPrepare() {
          Log.d(tag, "DOWNLOAD: PREPARING MOVE FILE")
        }
        override fun onFailed(errorCode: ErrorCode) {
          Log.e(tag, "DOWNLOAD: FAILED TO MOVE FILE $errorCode")
          downloadItemPart.failed = true
          downloadItemPart.isMoving = false
          file?.delete()
          checkDownloadItemFinished(downloadItem)
          currentDownloadItemParts.remove(downloadItemPart)
        }
        override fun onCompleted(result:Any) {
          Log.d(tag, "DOWNLOAD: FILE MOVE COMPLETED")
          val resultDocFile = result as DocumentFile
          Log.d(tag, "DOWNLOAD: COMPLETED FILE INFO ${resultDocFile.getAbsolutePath(mainActivity)}")

          // Rename to fix appended .mp4 on m4b files
          //  REF: https://github.com/anggrayudi/SimpleStorage/issues/94
          resultDocFile.renameTo(downloadItemPart.filename)

          downloadItemPart.moved = true
          downloadItemPart.isMoving = false
          checkDownloadItemFinished(downloadItem)
          currentDownloadItemParts.remove(downloadItemPart)
        }
      }

      val localFolderFile = DocumentFileCompat.fromUri(mainActivity, Uri.parse(downloadItemPart.localFolderUrl))
      if (localFolderFile == null) {
        // fAILED
        downloadItemPart.failed = true
        Log.e(tag, "Local Folder File from uri is null")
        checkDownloadItemFinished(downloadItem)
        currentDownloadItemParts.remove(downloadItemPart)
      } else {
        downloadItemPart.isMoving = true
        val mimetype = if (downloadItemPart.audioTrack != null) MimeType.AUDIO else MimeType.IMAGE
        val fileDescription = FileDescription(downloadItemPart.filename, downloadItemPart.itemTitle, mimetype)
        file?.moveFileTo(mainActivity, localFolderFile, fileDescription, fcb)
      }

    } else if (downloadCheckStatus != DownloadCheckStatus.InProgress) {
      checkDownloadItemFinished(downloadItem)
      currentDownloadItemParts.remove(downloadItemPart)
    }
  }

  private fun checkDownloadItemFinished(downloadItem:DownloadItem) {
    if (downloadItem.isDownloadFinished) {
      Log.i(tag, "Download Item finished ${downloadItem.media.metadata.title}")

      val downloadItemScanResult = folderScanner.scanDownloadItem(downloadItem)
      Log.d(tag, "Item download complete ${downloadItem.itemTitle} | local library item id: ${downloadItemScanResult?.localLibraryItem?.id}")

      val jsobj = JSObject()
      jsobj.put("libraryItemId", downloadItem.id)
      jsobj.put("localFolderId", downloadItem.localFolder.id)

      downloadItemScanResult?.localLibraryItem?.let { localLibraryItem ->
        jsobj.put("localLibraryItem", JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
      }
      downloadItemScanResult?.localMediaProgress?.let { localMediaProgress ->
        jsobj.put("localMediaProgress", JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
      }

      clientEventEmitter.onDownloadItemComplete(jsobj)
      downloadItemQueue.remove(downloadItem)
      DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
    }
  }
}
