/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.http.impl.server.{ Util, UnmarshallerImpl }
import akka.http.javadsl.model.{ HttpEntity, HttpMessage }
import akka.http.scaladsl.unmarshalling.{ Unmarshaller ⇒ ScalaUnmarshaller, FromMessageUnmarshaller }
import akka.japi.function.Function
import akka.util.ByteString

import scala.reflect.ClassTag

object Unmarshallers {
  def String: Unmarshaller[String] = implicitInstance
  def ByteString: Unmarshaller[ByteString] = implicitInstance
  def ByteArray: Unmarshaller[Array[Byte]] = implicitInstance
  def CharArray: Unmarshaller[Array[Char]] = implicitInstance

  def fromMessage[T](convert: Function[HttpMessage, T], clazz: Class[T]): Unmarshaller[T] =
    new UnmarshallerImpl[T](Util.scalaUnmarshallerFromFunction(convert))(ClassTag(clazz))

  def fromEntity[T](convert: Function[HttpEntity, T], clazz: Class[T]): Unmarshaller[T] =
    new UnmarshallerImpl[T](
      ScalaUnmarshaller.messageUnmarshallerFromEntityUnmarshaller(Util.scalaUnmarshallerFromFunction[HttpEntity, T](convert)))(ClassTag(clazz))

  private def implicitInstance[T: ClassTag](implicit um: FromMessageUnmarshaller[T]): Unmarshaller[T] =
    new UnmarshallerImpl[T](um)
}
