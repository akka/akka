/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.http.model.{ HttpEntity, MultipartByteRanges }

package object marshalling {
  def marshalUnsafe[T: Marshaller](value: T): HttpEntity = routing.FIXME
}

package marshalling {

  import akka.http.model.{ MultipartByteRanges, FormData }
  import akka.http.routing

  import scala.concurrent.Future

  trait Marshaller[-T]
  object Marshaller {
    implicit def stringMarshaller: Marshaller[String] = routing.FIXME
    implicit def bytesMarshaller: Marshaller[Array[Byte]] = routing.FIXME
    implicit def xmlMarshaller: Marshaller[scala.xml.NodeSeq] = routing.FIXME
    implicit def formDataMarshaller: Marshaller[FormData] = routing.FIXME
    implicit def entityMarshaller: Marshaller[HttpEntity] = routing.FIXME
    implicit def optionMarshaller[T: Marshaller]: Marshaller[Option[T]] = routing.FIXME
    implicit def futureMarshaller[T: Marshaller]: Marshaller[Future[T]] = routing.FIXME
    implicit def multipartByteRangesMarshaller: Marshaller[MultipartByteRanges] = routing.FIXME
  }

  trait ToResponseMarshaller[-T]
  object ToResponseMarshaller {
    implicit def fromMarshaller[T: Marshaller]: ToResponseMarshaller[T] = routing.FIXME
  }

  /** Something that can later be marshalled into a response */
  trait ToResponseMarshallable
  object ToResponseMarshallable {
    implicit def isMarshallable[T](value: T)(implicit marshaller: ToResponseMarshaller[T]): ToResponseMarshallable = routing.FIXME
    implicit def marshallableIsMarshallable: ToResponseMarshaller[ToResponseMarshallable] = routing.FIXME
  }
}
