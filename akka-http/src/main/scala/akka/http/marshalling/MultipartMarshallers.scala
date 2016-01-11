/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.concurrent.ExecutionContext
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.ActorRefFactory
import akka.parboiled2.util.Base64
import akka.stream.FlattenStrategy
import akka.stream.scaladsl._
import akka.http.engine.rendering.BodyPartRenderer
import akka.http.util.actorSystem
import akka.http.util.FastFuture._
import akka.http.model._
import MediaTypes._

trait MultipartMarshallers {
  protected val multipartBoundaryRandom: java.util.Random = ThreadLocalRandom.current()

  /**
   * Creates a new random 144-bit number and base64 encodes it (using a custom "safe" alphabet, yielding 24 characters).
   */
  def randomBoundary: String = {
    val array = new Array[Byte](18)
    multipartBoundaryRandom.nextBytes(array)
    Base64.custom.encodeToString(array, false)
  }

  implicit def multipartByteRangesMarshaller(implicit refFactory: ActorRefFactory): ToEntityMarshaller[MultipartByteRanges] =
    multipartPartsMarshaller[MultipartByteRanges](`multipart/byteranges`)(refFactory)
  implicit def multipartContentMarshaller(implicit refFactory: ActorRefFactory): ToEntityMarshaller[MultipartContent] =
    multipartPartsMarshaller[MultipartContent](`multipart/mixed`)(refFactory)

  private def multipartPartsMarshaller[T <: MultipartParts](mediaType: MultipartMediaType)(implicit refFactory: ActorRefFactory): ToEntityMarshaller[T] = {
    val boundary = randomBoundary
    val mediaTypeWithBoundary = mediaType withBoundary boundary
    Marshaller.withOpenCharset(mediaTypeWithBoundary) { (value, charset) ⇒
      val log = actorSystem(refFactory).log
      val bodyPartRenderer = new BodyPartRenderer(boundary, charset.nioCharset, partHeadersSizeHint = 128, log)
      val chunks = value.parts.transform("bodyPartRenderer", () ⇒ bodyPartRenderer).flatten(FlattenStrategy.concat)
      HttpEntity.Chunked(ContentType(mediaTypeWithBoundary), chunks)
    }
  }

  implicit def multipartFormDataMarshaller(implicit mcm: ToEntityMarshaller[MultipartContent],
                                           ec: ExecutionContext): ToEntityMarshaller[MultipartFormData] =
    Marshaller { value ⇒
      mcm(MultipartContent(value.parts)).fast.map {
        case Marshalling.WithOpenCharset(mt, marshal) ⇒
          val mediaType = `multipart/form-data` withBoundary mt.params("boundary")
          Marshalling.WithOpenCharset(mediaType, cs ⇒ MediaTypeOverrider.forEntity(marshal(cs), mediaType))
        case x ⇒ throw new IllegalStateException("ToRegularEntityMarshaller[MultipartContent] is expected to produce " +
          "a Marshalling.WithOpenCharset, not a " + x)
      }
    }
}

object MultipartMarshallers extends MultipartMarshallers
