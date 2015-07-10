/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import akka.http.impl.server.{ Util, UnmarshallerImpl }
import akka.http.javadsl.model.{ HttpEntity, HttpMessage }
import akka.http.scaladsl.unmarshalling.{ Unmarshaller ⇒ ScalaUnmarshaller, FromMessageUnmarshaller }
import akka.japi.function.Function
import akka.stream.Materializer
import akka.util.ByteString

import scala.reflect.ClassTag

object Unmarshallers {
  def String: Unmarshaller[String] = withMat(implicit mat ⇒ implicitly)
  def ByteString: Unmarshaller[ByteString] = withMat(implicit mat ⇒ implicitly)
  def ByteArray: Unmarshaller[Array[Byte]] = withMat(implicit mat ⇒ implicitly)
  def CharArray: Unmarshaller[Array[Char]] = withMat(implicit mat ⇒ implicitly)

  def fromMessage[T](convert: Function[HttpMessage, T], clazz: Class[T]): Unmarshaller[T] =
    new UnmarshallerImpl[T]({ (ec, mat) ⇒
      Util.scalaUnmarshallerFromFunction(convert)
    })(ClassTag(clazz))

  def fromEntity[T](convert: Function[HttpEntity, T], clazz: Class[T]): Unmarshaller[T] =
    new UnmarshallerImpl[T]({ (ec, mat) ⇒
      ScalaUnmarshaller.messageUnmarshallerFromEntityUnmarshaller(Util.scalaUnmarshallerFromFunction[HttpEntity, T](convert))
    })(ClassTag(clazz))

  private def withMat[T: ClassTag](f: Materializer ⇒ FromMessageUnmarshaller[T]): Unmarshaller[T] =
    new UnmarshallerImpl[T]({ (ec, mat) ⇒
      f(mat)
    })
}
