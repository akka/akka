/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.http.scaladsl.unmarshalling
import scala.concurrent.ExecutionContext
import scala.annotation.varargs
import akka.http.javadsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypeRange
import akka.http.scaladsl
import akka.http.javadsl.model.ContentType
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.RequestEntity
import akka.http.javadsl.model.MediaType
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._
import JavaScalaTypeEquivalence._

object Unmarshaller {
  // Allow java HttpEntity to be treated as scala HttpEntity
  private implicit val javaToScalaHttpEntity = JavaScalaTypeEquivalence.javaToScalaHttpEntity
  
  def wrap[A,B](scalaUnmarshaller: unmarshalling.Unmarshaller[A,B]) = new Unmarshaller()(scalaUnmarshaller)

  /**
   * Creates an unmarshaller from an asynchronous Java function. 
   */
  def async[A,B](f: java.util.function.Function[A,CompletionStage[B]]) = wrap(unmarshalling.Unmarshaller[A,B] {
    ctx => a => f.apply(a).toScala
  })
  
  /**
   * Creates an unmarshaller from a Java function.
   */
  def sync[A,B](f: java.util.function.Function[A,B]) = wrap(unmarshalling.Unmarshaller[A,B] {
    ctx => a => scala.concurrent.Future.successful(f.apply(a))
  })
  
  def entityToByteString = wrapFromHttpEntity(unmarshalling.Unmarshaller.byteStringUnmarshaller)
  def entityToByteArray = wrapFromHttpEntity(unmarshalling.Unmarshaller.byteArrayUnmarshaller)
  def entityToCharArray = wrapFromHttpEntity(unmarshalling.Unmarshaller.charArrayUnmarshaller)
  def entityToString = wrapFromHttpEntity(unmarshalling.Unmarshaller.stringUnmarshaller)
  def entityToUrlEncodedFormData = wrapFromHttpEntity(unmarshalling.Unmarshaller.defaultUrlEncodedFormDataUnmarshaller)
  
  def requestToEntity = wrap(unmarshalling.Unmarshaller[HttpRequest,RequestEntity] {
    ctx => request => scala.concurrent.Future.successful(request.entity())
  })
  
  def forMediaType[B](t: MediaType, um:Unmarshaller[_ <: HttpEntity,B]): Unmarshaller[HttpEntity,B] = {
    wrap(assumeScala(um.asScala).forContentTypes(t: scaladsl.model.MediaType))
  }
  
  def forMediaTypes[B](types: java.lang.Iterable[MediaType], um:Unmarshaller[_ <: HttpEntity,B]): Unmarshaller[HttpEntity,B] = {
    wrap(assumeScala(um.asScala).forContentTypes(types.asScala.toSeq.map(ContentTypeRange(_)): _*))
  }
  
  def firstOf[A, B] (u1: Unmarshaller[A, B], u2: Unmarshaller[A, B]): Unmarshaller[A, B] = {
    wrap(unmarshalling.Unmarshaller.firstOf(u1.asScala, u2.asScala))
  }
  
  def firstOf[A, B] (u1: Unmarshaller[A, B], u2: Unmarshaller[A, B], u3: Unmarshaller[A, B]): Unmarshaller[A, B] = {
    wrap(unmarshalling.Unmarshaller.firstOf(u1.asScala, u2.asScala, u3.asScala))
  }
  
  def firstOf[A, B] (u1: Unmarshaller[A, B], u2: Unmarshaller[A, B], u3: Unmarshaller[A, B], u4: Unmarshaller[A, B]): Unmarshaller[A, B] = {
    wrap(unmarshalling.Unmarshaller.firstOf(u1.asScala, u2.asScala, u3.asScala, u4.asScala))
  }
  
  def firstOf[A, B] (u1: Unmarshaller[A, B], u2: Unmarshaller[A, B], u3: Unmarshaller[A, B], u4: Unmarshaller[A, B], u5: Unmarshaller[A, B]): Unmarshaller[A, B] = {
    wrap(unmarshalling.Unmarshaller.firstOf(u1.asScala, u2.asScala, u3.asScala, u4.asScala, u5.asScala))
  }
  
  private def wrapFromHttpEntity[B](scalaUnmarshaller: unmarshalling.Unmarshaller[scaladsl.model.HttpEntity,B]) = {
    wrap(scalaUnmarshaller: unmarshalling.Unmarshaller[HttpEntity,B])
  }
    
}

/**
 * An unmarshaller transforms values of type A into type B.
 */
// asScala is implicit so we can just write "import unmarshaller.asScala" in Scala directive implementations.
final class Unmarshaller[A,B] private (implicit val asScala: unmarshalling.Unmarshaller[A,B]) {
  import unmarshalling.Unmarshaller._
  import Unmarshaller.wrap
    
  def map[C](f: java.util.function.Function[B,C]): Unmarshaller[A, C] = wrap(asScala.map(f.apply))
  
  def flatMap[C](f: java.util.function.Function[B,CompletionStage[C]]): Unmarshaller[A, C] = 
    wrap(asScala.flatMap { ctx => mat => b => f.apply(b).toScala })
  
  def flatMap[C](u: Unmarshaller[_ >: B,C]): Unmarshaller[A,C] =
    wrap(asScala.flatMap { ctx => mat => b => u.asScala.apply(b)(ctx,mat) })
    
  def mapWithInput[C](f: java.util.function.BiFunction[A, B, C]): Unmarshaller[A, C] =
    wrap(asScala.mapWithInput { case (a,b) => f.apply(a, b) })

  def flatMapWithInput[C](f: java.util.function.BiFunction[A, B, CompletionStage[C]]): Unmarshaller[A, C] =
    wrap(asScala.flatMapWithInput { case (a,b) => f.apply(a, b).toScala })
}
