/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.event.{ NoLogging, LoggingAdapter }
import akka.http.impl.engine.rendering.BodyPartRenderer
import akka.http.scaladsl.model._

trait MultipartMarshallers {
  implicit def multipartMarshaller[T <: Multipart](implicit log: LoggingAdapter = NoLogging): ToEntityMarshaller[T] =
    Marshaller strict { value ⇒
      val boundary = randomBoundary()
      val mediaType = value.mediaType withBoundary boundary
      Marshalling.WithOpenCharset(mediaType, { charset ⇒
        value.toEntity(charset, boundary)(log)
      })
    }

  /**
   * The random instance that is used to create multipart boundaries. This can be overriden (e.g. in tests) to
   * choose how a boundary is created.
   */
  protected def multipartBoundaryRandom: java.util.Random = ThreadLocalRandom.current()

  /**
   * The length of randomly generated multipart boundaries (before base64 encoding). Can be overridden
   * to configure.
   */
  protected def multipartBoundaryLength: Int = 18

  /**
   * The method used to create boundaries in `multipartMarshaller`. Can be overridden to create custom boundaries.
   */
  protected def randomBoundary(): String =
    BodyPartRenderer.randomBoundary(length = multipartBoundaryLength, random = multipartBoundaryRandom)
}

object MultipartMarshallers extends MultipartMarshallers
