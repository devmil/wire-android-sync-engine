/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.api

import android.net.Uri
import com.waz.ZLog._
import com.waz.api.impl.ProgressIndicator.ProgressData
import com.waz.api.impl.{ContentUriAssetForUpload, RecordingLevels, TranscodedVideoAsset}
import com.waz.bitmap.video.VideoTranscoder
import com.waz.model.{AssetData, AssetId, Mime}
import com.waz.service.ZMessaging
import com.waz.service.assets.GlobalRecordAndPlayService.{AssetMediaKey, RecordingCancelled, RecordingSuccessful}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.ui.SignalLoading
import com.waz.utils.ContentURIs
import com.waz.utils.events.Signal
import org.threeten.bp.Instant

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object AssetFactory {
  private implicit val logTag: LogTag = logTagFor(AssetFactory)

  trait LoadCallback {
    def onLoaded(asset: AssetForUpload): Unit
    def onFailed(): Unit
  }

  def getMaxAllowedAssetSizeInBytes = AssetData.MaxAllowedBackendAssetSizeInBytes

  /**
    * Gets an asset from a URI. Only the "content" scheme is supported
    */
  def fromContentUri(uri: Uri): AssetForUpload = {
    ContentUriAssetForUpload(AssetId(), uri)
  }

  def videoAsset(uri: Uri, callback: LoadCallback): ProgressIndicator = {
    import Threading.Implicits.Background

    val progress = Signal[ProgressData]()

    val context = ZMessaging.context
    val cache = ZMessaging.currentGlobal.cache
    val future = for {
      ContentURIs.MetaData(mime, name, _) <- CancellableFuture lift ContentURIs.queryContentUriMetaData(context, uri)
      entry <- CancellableFuture lift cache.createForFile(mime = Mime.Video.MP4, name = name)
      _ <- VideoTranscoder(context).apply(uri, entry.cacheFile, progress ! _)
    } yield {
      TranscodedVideoAsset(if (mime == Mime.Video.MP4) name else name.map(_ + ".mp4"), entry)
    }

    future .onComplete {
      case Success(asset) => callback.onLoaded(asset)
      case Failure(ex) =>
        warn(s"Video transcoding failed", ex)
        callback.onFailed()
    } (Threading.Ui)

    new impl.ProgressIndicator with SignalLoading {
      addLoader(_ => progress)(set)(ZMessaging.currentUi)
      override def cancel(): Unit = future.cancel()("progress_indicator")
    }
  }

  def recordAudioAsset(react: RecordingCallback): RecordingControls = {
    implicit val ui = ZMessaging.currentUi
    val service = ZMessaging.currentGlobal.recordingAndPlayback

    val key = AssetMediaKey(AssetId())
    val levels = new RecordingLevels(service.recordingLevel(key))
    service.record(key, 25.minutes).onComplete {
      case Success((instantOfStart, futureAsset)) =>
        react.onStart(instantOfStart)
        futureAsset.onComplete {
          case Success(RecordingSuccessful(asset, lengthLimitReached)) =>
            react.onComplete(asset, lengthLimitReached, levels.overview)
          case Success(RecordingCancelled) | Failure(_) =>
            react.onCancel()
        }(Threading.Ui)
      case Failure(cause) =>
        react.onCancel()
    }(Threading.Ui)

    new RecordingControls {
      override def stop(): Unit = service.stopRecording(key)
      override def cancel(): Unit = service.cancelRecording(key)
      override def soundLevels(windowSize: Int): UiSignal[Array[Float]] = impl.UiSignal(_ => levels.windowed(windowSize))
      override def finalize: Unit = cancel()
    }
  }
}

trait RecordingCallback {
  def onStart(timestamp: Instant): Unit
  def onComplete(recording: AudioAssetForUpload, fileSizeLimitReached: Boolean, overview: AudioOverview): Unit
  def onCancel(): Unit
}

trait RecordingControls {
  def stop(): Unit
  def cancel(): Unit

  def soundLevels(numberOfLevels: Int): UiSignal[Array[Float]] // single entry ∈ (0, 1) represents the peak "loudness" of the signal over a period of time
}

trait AudioOverview {
  def getLevels(numberOfLevels: Int): Array[Float]
  def isEmpty: Boolean
}
