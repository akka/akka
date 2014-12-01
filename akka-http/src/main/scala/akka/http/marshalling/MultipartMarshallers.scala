/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import akka.event.{ NoLogging, LoggingAdapter }

import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.parboiled2.util.Base64
import akka.stream.FlattenStrategy
import akka.stream.scaladsl._
import akka.http.engine.rendering.BodyPartRenderer
import akka.http.util.FastFuture
import akka.http.model._

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

  implicit def multipartMarshaller[T <: Multipart](implicit log: LoggingAdapter = NoLogging): ToEntityMarshaller[T] =
    Marshaller strict { value ⇒
      val boundary = randomBoundary
      val contentType = ContentType(value.mediaType withBoundary boundary)
      Marshalling.WithOpenCharset(contentType.mediaType, { charset ⇒
        value match {
          case x: Multipart.Strict ⇒
            val data = BodyPartRenderer.strict(x.strictParts, boundary, charset.nioCharset, partHeadersSizeHint = 128, log)
            HttpEntity(contentType, data)
          case _ ⇒
            val chunks = value.parts
              .transform(() ⇒ BodyPartRenderer.streamed(boundary, charset.nioCharset, partHeadersSizeHint = 128, log))
              .flatten(FlattenStrategy.concat)
            HttpEntity.Chunked(contentType, chunks)
        }
      })
    }
}

object MultipartMarshallers extends MultipartMarshallers
