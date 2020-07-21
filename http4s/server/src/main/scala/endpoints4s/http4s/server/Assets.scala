package endpoints4s.http4s.server

import java.net.URL

import cats.effect.{Blocker, ContextShift}
import cats.implicits._
import endpoints4s.algebra.Documentation
import endpoints4s.{Valid, algebra}
import fs2.io._
import org.http4s.headers._
import org.http4s._

trait Assets extends algebra.Assets with EndpointsWithCustomErrors {
  val DefaultBufferSize = 10240

  case class AssetRequest(
      assetPath: AssetPath,
      isGzipSupported: Boolean,
      ifModifiedSince: Option[HttpDate]
  )

  case class AssetPath(path: Seq[String], digest: Option[String], name: String)

  sealed trait AssetResponse
  object AssetResponse {
    case object NotFound extends AssetResponse
    case class Found(
        data: fs2.Stream[Effect, Byte],
        contentLength: Long,
        lastModified: Option[HttpDate],
        mediaType: Option[MediaType],
        isGzipped: Boolean,
        expired: Boolean
    ) extends AssetResponse
  }

  override def assetSegments(
      name: String,
      docs: Documentation
  ): Path[AssetPath] = {
    case p :+ s =>
      val i = s.lastIndexOf('-')
      val assetPath =
        if (i > 0) {
          val (name, digest) = s.splitAt(i)
          AssetPath(p, Some(digest.drop(1)), name)
        } else AssetPath(p, None, s)
      Some((Valid(assetPath), Nil))
    case Nil => None
  }

  private lazy val gzipSupport: RequestHeaders[Boolean] =
    headers => Valid(headers.get(`Accept-Encoding`).exists(_.satisfiedBy(ContentCoding.gzip)))

  private lazy val ifModifiedSince: RequestHeaders[Option[HttpDate]] =
    headers => Valid(headers.get(`If-Modified-Since`).map(_.date))

  private val assetResponse: Response[AssetResponse] = {
    case AssetResponse.NotFound                    => Response(NotFound)
    case AssetResponse.Found(_, _, _, _, _, false) => Response(NotModified)
    case AssetResponse.Found(data, length, lastModified, mediaType, isGzipped, true) =>
      val lastModifiedHeader = lastModified.map(`Last-Modified`(_))
      val contentTypeHeader = mediaType.map(`Content-Type`(_))
      val contentCodingHeader =
        if (isGzipped) Some(`Content-Encoding`(ContentCoding.gzip)) else None
      val contentLengthHeader =
        `Content-Length`.fromLong(length).getOrElse(`Transfer-Encoding`(TransferCoding.chunked))

      val headers = Headers(
        contentLengthHeader :: List(
          contentTypeHeader,
          contentCodingHeader,
          lastModifiedHeader
        ).flatten
      )

      Response(headers = headers, body = data)
  }

  override def assetsEndpoint(
      url: Url[AssetPath],
      docs: Documentation,
      notFoundDocs: Documentation
  ): Endpoint[AssetRequest, AssetResponse] = {
    val assetRequest =
      requestPartialInvariantFunctor
        .xmap(
          request(
            Get,
            url,
            headers = requestHeadersSemigroupal.product(gzipSupport, ifModifiedSince)
          ),
          (t: (AssetPath, Boolean, Option[HttpDate])) => AssetRequest(t._1, t._2, t._3),
          (assetRequest: AssetRequest) =>
            (assetRequest.assetPath, assetRequest.isGzipSupported, assetRequest.ifModifiedSince)
        )

    endpoint(assetRequest, assetResponse)
  }

  private def toResourceUrl(
      pathPrefix: Option[String],
      assetRequest: AssetRequest
  ): Option[(URL, Boolean)] = {
    val assetPath = assetRequest.assetPath
    val path =
      if (assetPath.path.nonEmpty)
        assetPath.path.mkString("", "/", s"/${assetPath.name}")
      else assetPath.name
    lazy val resourcePath = pathPrefix.getOrElse("") ++ s"/$path"

    def nonGzippedResourceUrl = Option(getClass.getResource(resourcePath)).map((_, false))
    def gzippedResourceUrl = Option(getClass.getResource(s"$resourcePath.gz")).map((_, true))
    def resourceUrl =
      if (assetRequest.isGzipSupported) gzippedResourceUrl.orElse(nonGzippedResourceUrl)
      else nonGzippedResourceUrl

    def hasDigest(digest: String) = digests.get(path).contains(digest)

    assetPath.digest match {
      case None                                => resourceUrl
      case Some(digest) if (hasDigest(digest)) => resourceUrl
      case Some(digest)                        => None
    }
  }

  private def toMediaType(name: String): Option[MediaType] =
    name.lastIndexOf('.') match {
      case -1 => None
      case i  => MediaType.forExtension(name.substring(i + 1))
    }

  def assetsResources(
      pathPrefix: Option[String] = None,
      blocker: Blocker
  )(implicit cs: ContextShift[Effect]): AssetRequest => AssetResponse =
    assetRequest =>
      toResourceUrl(pathPrefix, assetRequest)
        .map {
          case (url, isGzipped) =>
            val urlConnection = url.openConnection

            val ifModifiedSince = assetRequest.ifModifiedSince
            val lastModified =
              HttpDate.fromEpochSecond(urlConnection.getLastModified / 1000).toOption
            val expired = (ifModifiedSince, lastModified).mapN(_ < _).getOrElse(true)
            val contentLength = urlConnection.getContentLengthLong
            val mediaType = toMediaType(assetRequest.assetPath.name)
            val data = readInputStream(Effect.delay(url.openStream), DefaultBufferSize, blocker)

            AssetResponse.Found(
              data,
              contentLength,
              lastModified,
              mediaType,
              isGzipped,
              expired
            )
        }
        .getOrElse(AssetResponse.NotFound)
}
