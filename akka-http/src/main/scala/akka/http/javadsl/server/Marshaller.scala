/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import java.util.function
import akka.http.scaladsl.marshalling
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
import JavaScalaTypeEquivalence._

object Marshaller {
  def fromScala[A,B](scalaMarshaller: marshalling.Marshaller[A,B]) = new Marshaller()(scalaMarshaller)
  
  /** 
   * Safe downcasting of the output type of the marshaller to a superclass. 
   *  
   * Marshaller is covariant in B, i.e. if B2 is a subclass of B1, 
   * then Marshaller[X,B2] is OK to use where Marshaller[X,B1] is expected. 
   */
  def downcast[A, B1, B2 <: B1](m: Marshaller[A,B2]): Marshaller[A,B1] = m.asInstanceOf[Marshaller[A,B1]]

  /** 
   * Safe downcasting of the output type of the marshaller to a superclass. 
   *  
   * Marshaller is covariant in B, i.e. if B2 is a subclass of B1, 
   * then Marshaller[X,B2] is OK to use where Marshaller[X,B1] is expected. 
   */
  def downcast[A, B1, B2 <: B1](m: Marshaller[A,B2], target: Class[B1]): Marshaller[A,B1] = m.asInstanceOf[Marshaller[A,B1]]
  
  def stringToEntity: Marshaller[String,RequestEntity] = fromScala(marshalling.Marshaller.StringMarshaller)

  def byteArrayToEntity: Marshaller[Array[Byte],RequestEntity] = fromScala(marshalling.Marshaller.ByteArrayMarshaller)

  def charArrayToEntity: Marshaller[Array[Char],RequestEntity] = fromScala(marshalling.Marshaller.CharArrayMarshaller)
  
  def byteStringToEntity: Marshaller[ByteString,RequestEntity] = fromScala(marshalling.Marshaller.ByteStringMarshaller)
  
  def fromDataToEntity: Marshaller[FormData,RequestEntity] = fromScala(marshalling.Marshaller.FormDataMarshaller)
  
  def wrapEntity[A,C](f:function.BiFunction[ExecutionContext,C,A], m: Marshaller[A,_ <: RequestEntity], mediaType: MediaType): Marshaller[C,RequestEntity] = {
    val scalaMarshaller = assumeScala(downcast(m, classOf[RequestEntity]).asScala)
    fromScala(scalaMarshaller.wrapWithEC(mediaType) { ctx => c:C => f(ctx,c) } ) 
  }

  def wrapEntity[A,C](f:function.Function[C,A], m: Marshaller[A,_ <: RequestEntity], mediaType: MediaType): Marshaller[C,RequestEntity] = {
    val scalaMarshaller = assumeScala(downcast(m, classOf[RequestEntity]).asScala)
    fromScala(scalaMarshaller.wrap(mediaType)(f.apply)) 
  }

  def byteStringMarshaller(t: ContentType): Marshaller[ByteString, RequestEntity] = {
    fromScala(scaladsl.marshalling.Marshaller.byteStringMarshaller(t))
  }
  
  def opaque[A,B](f: function.Function[A,B]): Marshaller[A, B] = {
    fromScala(scaladsl.marshalling.Marshaller.opaque[A,B] { a => f.apply(a) })
  }
  
  def entityToOKResponse[A](m: Marshaller[A,_ <: RequestEntity]): Marshaller[A,HttpResponse] = {
    fromScala(marshalling.Marshaller.fromToEntityMarshaller[A]()(m.asScala))
  }
  
  def entityToResponse[A](status: StatusCode, m: Marshaller[A,_ <: RequestEntity]): Marshaller[A,HttpResponse] = {
    fromScala(marshalling.Marshaller.fromToEntityMarshaller[A](status)(m.asScala))
  }
  
  def entityToResponse[A](status: StatusCode, headers: java.lang.Iterable[HttpHeader], m: Marshaller[A,_ <: RequestEntity]): Marshaller[A,HttpResponse] = {
    fromScala(marshalling.Marshaller.fromToEntityMarshaller[A](status, headers.asScala.toVector)(m.asScala))
  }
  
  def entityToOKResponse[A](headers: java.lang.Iterable[HttpHeader], m: Marshaller[A,_ <: RequestEntity]): Marshaller[A,HttpResponse] = {
    fromScala(marshalling.Marshaller.fromToEntityMarshaller[A](headers = headers.asScala.toVector)(m.asScala))
  }
  
  def oneOf[A, B] (m1: Marshaller[A, B], m2: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala))
  }
  
  def oneOf[A, B] (m1: Marshaller[A, B], m2: Marshaller[A, B], m3: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala, m3.asScala))
  }
  
  def oneOf[A, B] (m1: Marshaller[A, B], m2: Marshaller[A, B], m3: Marshaller[A, B], m4: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala, m3.asScala, m4.asScala))
  }

  def oneOf[A, B] (m1: Marshaller[A, B], m2: Marshaller[A, B], m3: Marshaller[A, B], m4: Marshaller[A, B], m5: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala, m3.asScala, m4.asScala, m5.asScala))
  }
}

// [asScala] is made implicit so we can just do "import marshaller.asScala" in scala directive implementations
final class Marshaller[A,B] private (implicit val asScala: marshalling.Marshaller[A,B]) {
  import Marshaller.fromScala
  
  def map[C](f: function.Function[B,C]): Marshaller[A,C] = fromScala(asScala.map(f.apply))
  
  def compose[C](f: function.Function[C,A]): Marshaller[C,B] = fromScala(asScala.compose(f.apply))
}
