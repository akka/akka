/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import java.util.function
import akka.http.impl.util.JavaMapping
import akka.http.scaladsl.marshalling
import akka.japi.Util
import scala.concurrent.ExecutionContext
import akka.http.javadsl.model.ContentType
import akka.http.javadsl.model.MediaType
import akka.http.scaladsl
import akka.http.javadsl.model.HttpEntity
import akka.http.scaladsl.marshalling._
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.RequestEntity
import akka.util.ByteString
import akka.http.scaladsl.model.FormData
import akka.http.javadsl.model.StatusCode
import akka.http.javadsl.model.HttpHeader
import scala.collection.JavaConverters._
import akka.http.impl.util.JavaMapping.Implicits._

object Marshaller {

  import JavaMapping.Implicits._

  implicit def fromScala[A, B](scalaMarshaller: marshalling.Marshaller[A, B]) = new Marshaller()(scalaMarshaller)

  /**
   * Safe downcasting of the output type of the marshaller to a superclass. 
   *
   * Marshaller is covariant in B, i.e. if B2 is a subclass of B1, 
   * then Marshaller[X,B2] is OK to use where Marshaller[X,B1] is expected. 
   */
  def downcast[A, B1, B2 <: B1](m: Marshaller[A, B2]): Marshaller[A, B1] = m.asInstanceOf[Marshaller[A, B1]]

  /**
   * Safe downcasting of the output type of the marshaller to a superclass. 
   *
   * Marshaller is covariant in B, i.e. if B2 is a subclass of B1, 
   * then Marshaller[X,B2] is OK to use where Marshaller[X,B1] is expected. 
   */
  def downcast[A, B1, B2 <: B1](m: Marshaller[A, B2], target: Class[B1]): Marshaller[A, B1] = m.asInstanceOf[Marshaller[A, B1]]

  def stringToEntity: Marshaller[String, RequestEntity] = fromScala(marshalling.Marshaller.StringMarshaller)

  def byteArrayToEntity: Marshaller[Array[Byte], RequestEntity] = fromScala(marshalling.Marshaller.ByteArrayMarshaller)

  def charArrayToEntity: Marshaller[Array[Char], RequestEntity] = fromScala(marshalling.Marshaller.CharArrayMarshaller)

  def byteStringToEntity: Marshaller[ByteString, RequestEntity] = fromScala(marshalling.Marshaller.ByteStringMarshaller)

  def fromDataToEntity: Marshaller[FormData, RequestEntity] = fromScala(marshalling.Marshaller.FormDataMarshaller)

  // TODO make sure these are actually usable in a sane way

  def wrapEntity[A, C](f: function.BiFunction[ExecutionContext, C, A], m: Marshaller[A, RequestEntity], mediaType: MediaType): Marshaller[C, RequestEntity] = {
    val scalaMarshaller = m.asScalaToEntityMarshaller
    scalaMarshaller.wrapWithEC(mediaType.asScala) { ctx => c: C => f(ctx, c) } (ContentTypeOverrider.forEntity)
  }

  def wrapEntity[A, C, E <: RequestEntity](f: function.Function[C, A], m: Marshaller[A, E], mediaType: MediaType): Marshaller[C, RequestEntity] = {
    val scalaMarshaller = m.asScalaToEntityMarshaller
    fromScala(scalaMarshaller.wrap(mediaType.asScala)((in: C) => f.apply(in))(ContentTypeOverrider.forEntity))
  }

  def byteStringMarshaller(t: ContentType): Marshaller[ByteString, RequestEntity] = {
    scaladsl.marshalling.Marshaller.byteStringMarshaller(t.asScala)
  }

  def opaque[A, B](f: function.Function[A, B]): Marshaller[A, B] = {
    scaladsl.marshalling.Marshaller.opaque[A, B] { a => f.apply(a) }
  }

  def entityToOKResponse[A](m: Marshaller[A, _ <: RequestEntity]): Marshaller[A, HttpResponse] = {
    marshalling.Marshaller.fromToEntityMarshaller[A]()(m.asScalaToEntityMarshaller)
  }

  def entityToResponse[A, R <: RequestEntity](status: StatusCode, m: Marshaller[A, R]): Marshaller[A, HttpResponse] = {
    marshalling.Marshaller.fromToEntityMarshaller[A](status.asScala)(m.asScalaToEntityMarshaller)
  }

  def entityToResponse[A](status: StatusCode, headers: java.lang.Iterable[HttpHeader], m: Marshaller[A, _ <: RequestEntity]): Marshaller[A, HttpResponse] = {
    marshalling.Marshaller.fromToEntityMarshaller[A](status.asScala, Util.immutableSeq(headers).map(_.asScala))(m.asScalaToEntityMarshaller) // TODO can we avoid the map() ?
  }

  def entityToOKResponse[A](headers: java.lang.Iterable[HttpHeader], m: Marshaller[A, _ <: RequestEntity]): Marshaller[A, HttpResponse] = {
    marshalling.Marshaller.fromToEntityMarshaller[A](headers = Util.immutableSeq(headers).map(_.asScala))(m.asScalaToEntityMarshaller) // TODO avoid the map()
  }

  def oneOf[A, B](m1: Marshaller[A, B], m2: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala))
  }

  def oneOf[A, B](m1: Marshaller[A, B], m2: Marshaller[A, B], m3: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala, m3.asScala))
  }

  def oneOf[A, B](m1: Marshaller[A, B], m2: Marshaller[A, B], m3: Marshaller[A, B], m4: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala, m3.asScala, m4.asScala))
  }

  def oneOf[A, B](m1: Marshaller[A, B], m2: Marshaller[A, B], m3: Marshaller[A, B], m4: Marshaller[A, B], m5: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala, m3.asScala, m4.asScala, m5.asScala))
  }

  implicit def asScalaToResponseMarshaller[T](m: Marshaller[T, akka.http.javadsl.model.HttpResponse]): ToResponseMarshaller[T] =
    m.asScala.map(_.asScala)

  implicit def asScalaEntityMarshaller[T](m: Marshaller[T, akka.http.javadsl.model.RequestEntity]): akka.http.scaladsl.marshalling.Marshaller[T, akka.http.scaladsl.model.RequestEntity] =
    m.asScala.map(_.asScala)
}

// [asScala] is made implicit so we can just do "import marshaller.asScala" in scala directive implementations
final class Marshaller[A, B] private(implicit val asScala: marshalling.Marshaller[A, B]) {
  import Marshaller.fromScala

  // TODO would be nice to not need this special case
  def asScalaToEntityMarshaller[C]: marshalling.Marshaller[A, C] = asScala.asInstanceOf[marshalling.Marshaller[A, C]]

  def map[C](f: function.Function[B, C]): Marshaller[A, C] = fromScala(asScala.map(f.apply))

  def compose[C](f: function.Function[C, A]): Marshaller[C, B] = fromScala(asScala.compose(f.apply))
}
