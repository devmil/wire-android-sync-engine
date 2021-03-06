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
package com.waz.ui

import android.content.Context
import android.graphics.Bitmap
import com.waz.ZLog._
import com.waz.bitmap
import com.waz.model.AssetId
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.ui.MemoryImageCache.BitmapRequest.{Regular, Round, Single}
import com.waz.utils.TrimmingLruCache
import com.waz.utils.TrimmingLruCache.CacheSize

class MemoryImageCache(val context: Context) {
  import MemoryImageCache._
  private implicit val logTag: LogTag = logTagFor[MemoryImageCache]

  /**
   * In memory image cache.
   */
  private val lru = new TrimmingLruCache[Key, Entry](context, CacheSize(total => math.max(5 * 1024 * 1024, (total - 30 * 1024 * 1024) / 2))) {
    override def sizeOf(id: Key, value: Entry): Int = value.size
  }

  def get(id: AssetId, req: BitmapRequest, imgWidth: Int): Option[Bitmap] =
    Option(lru.get(Key(id, tag(req)))) flatMap {
      case BitmapEntry(bmp) if bmp.getWidth >= req.width || (imgWidth > 0 && bmp.getWidth > imgWidth) => Some(bmp)
      case _ => None
    }

  def add(id: AssetId, req: BitmapRequest, bitmap: Bitmap): Unit = if (bitmap != null && bitmap != Images.EmptyBitmap) {
    lru.put(Key(id, tag(req)), BitmapEntry(bitmap))
  }

  def remove(id: AssetId, req: BitmapRequest): Unit = lru.remove(Key(id, tag(req)))

  def reserve(id: AssetId,  req: BitmapRequest, width: Int, height: Int): Unit = reserve(id, req, width * height * 4 + 256)

  def reserve(id: AssetId, req: BitmapRequest, size: Int): Unit = lru.synchronized {
    val key = Key(id, tag(req))
    Option(lru.get(key)) getOrElse lru.put(key, EmptyEntry(size))
  }

  def apply(id: AssetId, req: BitmapRequest, imgWidth: Int, load: => CancellableFuture[Bitmap]): CancellableFuture[Bitmap] =
    get(id, req, imgWidth) match {
      case Some(bitmap) =>
        verbose(s"found bitmap for req: $req")
        CancellableFuture.successful(bitmap)
      case None =>
        verbose(s"no bitmap for req: $req, loading...")
        val future = load
        future.onSuccess {
          case bitmap.EmptyBitmap => // ignore
          case img => add(id, req, img)
        }(Threading.Ui)
        future
    }
}

object MemoryImageCache {

  case class Key(id: AssetId, string: String)

  sealed trait Entry {
    def size: Int
  }

  case class BitmapEntry(bitmap: Bitmap) extends Entry {
    override def size = bitmap.getByteCount
  }

  // used to reserve space
  case class EmptyEntry(size: Int) extends Entry {
    require(size > 0)
  }

  sealed trait BitmapRequest {
    val width: Int
    val mirror: Boolean = false
  }

  object BitmapRequest {
    case class Regular(width: Int = 0, override val mirror: Boolean = false) extends BitmapRequest
    case class Single(width: Int = 0, override val mirror: Boolean = false) extends BitmapRequest
    case class Round(width: Int = 0, borderWidth: Int = 0, borderColor: Int = 0) extends BitmapRequest
  }

  //The width makes BitmapRequests themselves bad keys, remove them
  private def tag(request: BitmapRequest) = request match {
    case Regular(_, mirror) => s"Regular-$mirror"
    case Single(_, mirror) => s"Single-$mirror"
    case Round(_, bw, bc) => s"Round-$bw-$bc"
  }
}
