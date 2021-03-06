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
package com.waz.model

import android.database.Cursor
import android.net.Uri
import android.util.Base64
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.verbose
import com.waz.content.WireContentProvider
import com.waz.db.Col._
import com.waz.db.Dao
import com.waz.model.AssetMetaData.Image
import com.waz.model.AssetStatus.UploadDone
import com.waz.model.otr.SignalingKey
import com.waz.service.ZMessaging
import com.waz.service.downloads.DownloadRequest._
import com.waz.utils.JsonDecoder.{apply => _, opt => _}
import com.waz.utils._
import org.json.JSONObject
import org.threeten.bp.Duration

import scala.util.Try

case class AssetData(id:          AssetId               = AssetId(),
                     mime:        Mime                  = Mime.Unknown,
                     sizeInBytes: Long                  = 0L,
                     status:      AssetStatus           = AssetStatus.UploadNotStarted,
                     remoteId:    Option[RAssetId]      = None,
                     token:       Option[AssetToken]    = None,
                     otrKey:      Option[AESKey]        = None,
                     sha:         Option[Sha256]        = None,
                     name:        Option[String]        = None,
                     previewId:   Option[AssetId]       = None,
                     metaData:    Option[AssetMetaData] = None,
                     source:      Option[Uri]           = None,
                     proxyPath:   Option[String]        = None,
                     //TODO remove v2 attributes when transition period is over
                     convId:      Option[RConvId]       = None,
                     //data only used for temporary caching and legacy reasons - shouldn't be stored in AssetsStorage where possible
                     data:        Option[Array[Byte]]   = None,
                     v2ProfileId: Option[RAssetId]      = None,
                     //TODO remove after v2 transtion period (eases database migration)
                     assetType:   Option[AssetType]     = None
                    ) {

  import AssetData._

  override def toString: String =
    s"""
       |AssetData:
       | id:            $id
       | mime:          $mime
       | sizeInBytes:   $sizeInBytes
       | status:        $status
       | rId:           $remoteId
       | token:         $token
       | otrKey:        $otrKey
       | sha:           $sha
       | preview:       $previewId
       | metaData:      $metaData
       | convId:        $convId
       | data (length): ${data.map(_.length).getOrElse(0)}
       | other fields:  $name, $source, $proxyPath, $v2ProfileId
    """.stripMargin

  lazy val size = data.fold(sizeInBytes)(_.length)

  //be careful when accessing - can be expensive
  lazy val data64 = data.map(Base64.encodeToString(_, Base64.NO_WRAP | Base64.NO_PADDING))

  lazy val fileExtension = mime.extension

  lazy val remoteData = (remoteId, token, otrKey, sha) match {
    case (None, None, None, None) => Option.empty[RemoteData]
    case _ => Some(RemoteData(remoteId, token, otrKey, sha))
  }

  lazy val cacheKey = {
    val key = (proxyPath, source) match {
      case (Some(proxy), _) => CacheKey(proxy)
      case (_, Some(uri)) if !NonKeyURIs.contains(uri) => CacheKey.fromUri(uri)
      case _ => CacheKey.fromAssetId(id)
    }
    verbose(s"created cache key: $key for asset: $id")
    key
  }

  def loadRequest = {
    val req = (remoteData, v2ProfileId, source, proxyPath) match {
      case (Some(rData), _, _, _)                      => WireAssetRequest(cacheKey, id, rData, convId, mime)
      case (_, Some(v2Id), _, _)                       => WireAssetRequest(cacheKey, id, RemoteData(Some(v2Id)), convId, mime)
      case (_, _, Some(uri), _) if isExternalUri(uri)  => External(cacheKey, uri)
      case (_, _, Some(uri), _)                        => LocalAssetRequest(cacheKey, uri, mime, name)
      case (_, _, None, Some(path))                    => Proxied(cacheKey, path)
      case _                                           => CachedAssetRequest(cacheKey, mime, name)
    }
    verbose(s"loadRequest returning: $req")
    req
  }

  val (isImage, isVideo, isAudio) = this match {
    case IsImage() => (true, false, false)
    case IsVideo() => (false, true, false)
    case IsAudio() => (false, false, true)
    case _         => (false, false, false)
  }

  val tag = this match {
    case IsImageWithTag(t) => t
    case _ => Image.Tag.Empty
  }

  val dimensions = this match {
    case WithDimensions(dim) => dim
    case _ => Dim2(0, 0)
  }

  val width = dimensions.width
  val height = dimensions.height

  def copyWithRemoteData(remoteData: RemoteData) = {
    val res = copy(
      remoteId  = remoteData.remoteId,
      token     = remoteData.token,
      otrKey    = remoteData.otrKey,
      sha       = remoteData.sha256
    )
    res.copy(status = res.remoteData.fold(res.status)(_ => UploadDone))
  }
}

object AssetData {

  /**
    * Do not use these URIs as cache keys, as they do not provide a unique identifier to the asset downloaded from them
    */
  val NonKeyURIs = Set(
    "https://source.unsplash.com/800x800/?landscape"
  ).map(Uri.parse)

  def decodeData(data64: String): Array[Byte] = Base64.decode(data64, Base64.NO_PADDING | Base64.NO_WRAP)

  def cacheKeyFrom(uri: Uri): CacheKey = WireContentProvider.CacheUri.unapply(ZMessaging.context)(uri).getOrElse(CacheKey(uri.toString))

  def isExternalUri(uri: Uri): Boolean = Option(uri.getScheme).forall(_.startsWith("http"))

  //simplify handling remote data from asset data
  case class RemoteData(remoteId: Option[RAssetId]    = None,
                        token:    Option[AssetToken]  = None,
                        otrKey:   Option[AESKey]      = None,
                        sha256:   Option[Sha256]      = None
                       )

  //needs to be def to create new id each time. "medium" tag ensures it will not be ignored by MessagesService
  def newImageAsset(id: AssetId = AssetId(), tag: Image.Tag) = AssetData(id = id, metaData = Some(AssetMetaData.Image(Dim2(0, 0), tag)))

  def newImageAssetFromUri(id: AssetId = AssetId(), tag: Image.Tag, uri: Uri) = AssetData(id = id, metaData = AssetMetaData.Image(ZMessaging.context, uri, tag), source = Some(uri))

  val Empty = AssetData()

  object WithMetaData {
    def unapply(asset: AssetData): Option[AssetMetaData] = asset.metaData
  }

  object IsImage {
    def unapply(asset: AssetData): Boolean = Mime.Image.unapply(asset.mime) || (asset.metaData match {
      case Some(AssetMetaData.Image(dims, tag)) => true
      case _ => false
    })
  }

  object IsVideo {
    def unapply(asset: AssetData): Boolean = Mime.Video.unapply(asset.mime) || (asset.metaData match {
      case Some(AssetMetaData.Video(_, _)) => true
      case _ => false
    })
  }

  object IsAudio {
    def unapply(asset: AssetData): Boolean = Mime.Audio.unapply(asset.mime) || (asset.metaData match {
      case Some(AssetMetaData.Audio(_, _)) => true
      case _ => false
    })
  }

  object WithDimensions {
    def unapply(asset: AssetData): Option[Dim2] = asset.metaData match {
      case Some(AssetMetaData.HasDimensions(dimensions)) => Some(dimensions)
      case _ => None
    }
  }

  object IsImageWithTag {
    def unapply(asset: AssetData): Option[Image.Tag] = asset.metaData match {
      case Some(AssetMetaData.Image(_, tag)) => Some(tag)
      case _ => None
    }
  }

  object WithDuration {
    def unapply(asset: AssetData): Option[Duration] = asset.metaData match {
      case Some(AssetMetaData.HasDuration(duration)) => Some(duration)
      case _ => None
    }
  }

  object WithPreview {
    def unapply(asset: AssetData): Option[AssetId] = asset.previewId
  }

  object WithRemoteId {
    def unapply(asset: AssetData): Option[RAssetId] = asset.remoteId
  }

  object WithStatus {
    def unapply(asset: AssetData): Option[AssetStatus] = Some(asset.status)
  }

  object WithSource {
    def unapply(asset: AssetData): Option[Uri] = asset.source
  }

  val MaxAllowedAssetSizeInBytes = 26214383L
  // 25MiB - 32 + 15 (first 16 bytes are AES IV, last 1 (!) to 16 bytes are padding)
  val MaxAllowedBackendAssetSizeInBytes = 26214400L

  // 25MiB

  case class ProcessingTaskKey(id: AssetId)

  case class UploadTaskKey(id: AssetId)

  implicit object AssetDataDao extends Dao[AssetData, AssetId] {
    val Id    = id[AssetId]('_id, "PRIMARY KEY").apply(_.id)
    val Asset = text[AssetType]('asset_type, _.name, AssetType.valueOf)(_ => AssetType.Empty)
    val Data = text('data)(JsonEncoder.encodeString(_))

    override val idCol = Id
    override val table = Table("Assets", Id, Asset, Data)

    override def apply(implicit cursor: Cursor): AssetData = {
      val tpe: AssetType = Asset
      tpe match {
        case AssetType.Image => JsonDecoder.decode(Data)(ImageAssetDataDecoder)
        case AssetType.Any   => JsonDecoder.decode(Data)(AnyAssetDataDecoder)
        case _               => JsonDecoder.decode(Data)(AssetDataDecoder)
      }
    }
  }

  implicit lazy val AssetDataEncoder: JsonEncoder[AssetData] = new JsonEncoder[AssetData] {
    override def apply(data: AssetData): JSONObject = JsonEncoder { o =>
      o.put("id",           data.id.str)
      o.put("mime",         data.mime.str)
      o.put("sizeInBytes",  data.sizeInBytes)
      o.put("status",       JsonEncoder.encode(data.status))
      data.remoteId     foreach (v => o.put("remoteId",     v.str))
      data.token        foreach (v => o.put("token",        v.str))
      data.otrKey       foreach (v => o.put("otrKey",       v.str))
      data.sha          foreach (v => o.put("sha256",       v.str))
      data.name         foreach (v => o.put("name",         v))
      data.previewId    foreach (v => o.put("preview",      v.str))
      data.metaData     foreach (v => o.put("metaData",     JsonEncoder.encode(v)))
      data.source       foreach (v => o.put("source",       v.toString))
      data.proxyPath    foreach (v => o.put("proxyPath",    v))
      data.convId       foreach (v => o.put("convId",       v.str))
      data.data64       foreach (v => o.put("data64",       v))
      data.v2ProfileId  foreach (v => o.put("v2ProfileId",  v))
    }
  }

  lazy val AssetDataDecoder: JsonDecoder[AssetData] = new JsonDecoder[AssetData] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): AssetData =
      AssetData(
        'id,
        Mime('mime),
        'sizeInBytes,
        JsonDecoder[AssetStatus]('status),
        decodeOptRAssetId('remoteId),
        decodeOptString('token).map(AssetToken(_)),
        decodeOptString('otrKey).map(AESKey(_)),
        decodeOptString('sha256).map(Sha256(_)),
        'name,
        'preview,
        opt[AssetMetaData]('metaData),
        decodeOptString('source).map(Uri.parse),
        'proxyPath,
        'convId,
        decodeOptString('data).map(decodeData),
        decodeOptRAssetId('v2ProfileId)
      )
  }


  //TODO Dean: remove after v2 transition period
  //This decoder is used to decode the old ImageAssetData type from SQL storage. We just need enough information to re-download it again
  lazy val ImageAssetDataDecoder: JsonDecoder[AssetData] = new JsonDecoder[AssetData] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): AssetData = {
      verbose(s"decoding ImageAssetData: $js")
      val id = decodeId[AssetId]('id)
      val convId = decodeOptRConvId('convId)

      Try(js.getJSONArray("versions")).toOption.flatMap { arr =>
        Seq.tabulate(arr.length())(arr.getJSONObject).map{ obj =>
          verbose(s"applying ImageDataDecoder to $obj")
          ImageDataDecoder.apply(obj)
        }.collect {
          case a@AssetData.IsImageWithTag(Image.Tag.Medium) => a.copy(id = id, convId = convId)
        }.headOption
      }.getOrElse(AssetData(id, convId = convId))
    }
  }

  //TODO Dean: remove after v2 transition period
  //This decoder is used to decode the old ImageData type from SQL storage. We just need enough information to re-download it again
  lazy val ImageDataDecoder: JsonDecoder[AssetData] = new JsonDecoder[AssetData] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): AssetData = {
      verbose(s"decoding ImageData: $js")
      val otrKey = js.opt("otrKey") match {
        case o: JSONObject => Try(implicitly[JsonDecoder[SignalingKey]].apply(o).encKey).toOption
        case str: String => Some(AESKey(str))
        case _ => None
      }

      AssetData(
        mime = Mime('mime),
        sizeInBytes = 'size,
        metaData = Some(AssetMetaData.Image(Dim2('width, 'height), Image.Tag('tag))),
        source = decodeOptString('url).map(Uri.parse),
        proxyPath = decodeOptString('proxyPath)
      ).copyWithRemoteData(RemoteData('remoteId, None, otrKey, decodeOptString('sha256).map(Sha256(_))))
    }
  }

  //TODO Dean: remove after v2 transition period
  //This decoder is used to decode the old AnyAssetData type from SQL storage. We just need enough information to re-download it again
  implicit lazy val AnyAssetDataDecoder: JsonDecoder[AssetData] = new JsonDecoder[AssetData] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): AssetData = {
      verbose(s"decoding AnyAssetData: $js")
      val remoteData = decodeOptObject('key)(js.getJSONObject("status")).map { key =>
        val remoteId = decodeOptRAssetId('remoteId)(key).orElse(decodeOptRAssetId('remoteKey)(key))
        val token = decodeOptString('token)(key).map(AssetToken)
        val otrKey = decodeOptString('otrKey)(key).map(AESKey(_))
        val sha = decodeOptString('sha256)(key).map(Sha256(_))
        RemoteData(remoteId, token, otrKey, sha)
      }

      val mime = Mime('mimeType)
      val source = mime match {
        case Mime.Audio() => None //we don't want the unencoded url stored previously - use id as cache key instead
        case _ => decodeOptString('source).map(Uri.parse)
      }

      val asset = AssetData(
        decodeAssetId('id),
        mime = mime,
        sizeInBytes = 'sizeInBytes,
        name = 'name,
        convId = decodeOptRConvId('convId),
        source = source
      )

      remoteData.map(asset.copyWithRemoteData).getOrElse(asset)
    }
  }

}

case class AssetToken(str: String) extends AnyVal

object AssetToken extends (String => AssetToken)


