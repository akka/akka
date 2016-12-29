/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.unmarshalling

import java.util.concurrent.CompletionStage

import akka.http.impl.util.JavaMapping
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.model.{ HttpEntity, HttpRequest, MediaType, RequestEntity }
import akka.http.scaladsl.model.{ ContentTypeRange, ContentTypes, FormData, Multipart }
import akka.http.scaladsl.unmarshalling
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller.{ EnhancedFromEntityUnmarshaller, UnsupportedContentTypeException }
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.util.ByteString

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

object Unmarshaller {
  implicit def fromScala[A, B](scalaUnmarshaller: unmarshalling.Unmarshaller[A, B]): Unmarshaller[A, B] =
    scalaUnmarshaller

  /**
   * Creates an unmarshaller from an asynchronous Java function.
   */
  def async[A, B](f: java.util.function.Function[A, CompletionStage[B]]): Unmarshaller[A, B] =
    unmarshalling.Unmarshaller[A, B] { ctx ⇒ a ⇒ f(a).toScala
    }

  /**
   * Creates an unmarshaller from a Java function.
   */
  def sync[A, B](f: java.util.function.Function[A, B]): Unmarshaller[A, B] =
    unmarshalling.Unmarshaller[A, B] { ctx ⇒ a ⇒ scala.concurrent.Future.successful(f.apply(a))
    }

  // format: OFF
  def entityToByteString: Unmarshaller[HttpEntity, ByteString]       = unmarshalling.Unmarshaller.byteStringUnmarshaller
  def entityToByteArray: Unmarshaller[HttpEntity, Array[Byte]]       = unmarshalling.Unmarshaller.byteArrayUnmarshaller
  def entityToCharArray: Unmarshaller[HttpEntity, Array[Char]]       = unmarshalling.Unmarshaller.charArrayUnmarshaller
  def entityToString: Unmarshaller[HttpEntity, String]               = unmarshalling.Unmarshaller.stringUnmarshaller
  def entityToUrlEncodedFormData: Unmarshaller[HttpEntity, FormData] = unmarshalling.Unmarshaller.defaultUrlEncodedFormDataUnmarshaller
  def entityToMultipartByteRanges: Unmarshaller[HttpEntity, Multipart.ByteRanges] = unmarshalling.MultipartUnmarshallers.defaultMultipartByteRangesUnmarshaller
  // format: ON

  val requestToEntity: Unmarshaller[HttpRequest, RequestEntity] =
    unmarshalling.Unmarshaller.strict[HttpRequest, RequestEntity](_.entity)

  def forMediaType[B](t: MediaType, um: Unmarshaller[HttpEntity, B]): Unmarshaller[HttpEntity, B] = {
    unmarshalling.Unmarshaller.withMaterializer[HttpEntity, B] { implicit ex ⇒ implicit mat ⇒ jEntity ⇒ {
      val entity = jEntity.asScala
      val mediaType = t.asScala
      if (entity.contentType == ContentTypes.NoContentType || mediaType.matches(entity.contentType.mediaType)) {
        um.asScala(entity)
      } else FastFuture.failed(UnsupportedContentTypeException(ContentTypeRange(t.toRange.asScala)))
    }
    }
  }

  def forMediaTypes[B](types: java.lang.Iterable[MediaType], um: Unmarshaller[HttpEntity, B]): Unmarshaller[HttpEntity, B] = {
    val u: FromEntityUnmarshaller[B] = um.asScala
    val theTypes: Seq[akka.http.scaladsl.model.ContentTypeRange] = types.asScala.toSeq.map { media ⇒
      akka.http.scaladsl.model.ContentTypeRange(media.asScala)
    }
    u.forContentTypes(theTypes: _*)
  }

  def firstOf[A, B](u1: Unmarshaller[A, B], u2: Unmarshaller[A, B]): Unmarshaller[A, B] = {
    unmarshalling.Unmarshaller.firstOf(u1.asScala, u2.asScala)
  }

  def firstOf[A, B](u1: Unmarshaller[A, B], u2: Unmarshaller[A, B], u3: Unmarshaller[A, B]): Unmarshaller[A, B] = {
    unmarshalling.Unmarshaller.firstOf(u1.asScala, u2.asScala, u3.asScala)
  }

  def firstOf[A, B](u1: Unmarshaller[A, B], u2: Unmarshaller[A, B], u3: Unmarshaller[A, B], u4: Unmarshaller[A, B]): Unmarshaller[A, B] = {
    unmarshalling.Unmarshaller.firstOf(u1.asScala, u2.asScala, u3.asScala, u4.asScala)
  }

  def firstOf[A, B](u1: Unmarshaller[A, B], u2: Unmarshaller[A, B], u3: Unmarshaller[A, B], u4: Unmarshaller[A, B], u5: Unmarshaller[A, B]): Unmarshaller[A, B] = {
    unmarshalling.Unmarshaller.firstOf(u1.asScala, u2.asScala, u3.asScala, u4.asScala, u5.asScala)
  }

  private implicit def adaptInputToJava[JI, SI, O](um: unmarshalling.Unmarshaller[SI, O])(implicit mi: JavaMapping[JI, SI]): unmarshalling.Unmarshaller[JI, O] =
    um.asInstanceOf[unmarshalling.Unmarshaller[JI, O]] // since guarantee provided by existence of `mi`

}

trait UnmarshallerBase[-A, B]

/**
 * An unmarshaller transforms values of type A into type B.
 */
abstract class Unmarshaller[-A, B] extends UnmarshallerBase[A, B] {

  implicit def asScala: akka.http.scaladsl.unmarshalling.Unmarshaller[A, B]

  /** INTERNAL API */
  private[akka] def asScalaCastInput[I]: unmarshalling.Unmarshaller[I, B] = asScala.asInstanceOf[unmarshalling.Unmarshaller[I, B]]

  /**
   * Apply this Unmarshaller to the given value.
   */
  def unmarshal(value: A, ec: ExecutionContext, mat: Materializer): CompletionStage[B] = asScala.apply(value)(ec, mat).toJava

  /**
   * Deprecated in favor of [[unmarshal]].
   */
  @deprecated("Use unmarshal instead.", "10.0.2")
  def unmarshall(a: A, ec: ExecutionContext, mat: Materializer): CompletionStage[B] = unmarshal(a, ec, mat)

  /**
   * Transform the result `B` of this unmarshaller to a `C` producing a marshaller that turns `A`s into `C`s
   *
   * @return A new marshaller that can unmarshall instances of `A` into instances of `C`
   */
  def thenApply[C](f: java.util.function.Function[B, C]): Unmarshaller[A, C] = asScala.map(f.apply)

  def flatMap[C](f: java.util.function.Function[B, CompletionStage[C]]): Unmarshaller[A, C] =
    asScala.flatMap { ctx ⇒ mat ⇒ b ⇒ f.apply(b).toScala }

  def flatMap[C](u: Unmarshaller[_ >: B, C]): Unmarshaller[A, C] =
    asScala.flatMap { ctx ⇒ mat ⇒ b ⇒ u.asScala.apply(b)(ctx, mat) }

  // TODO not exposed for Java yet
  //  def mapWithInput[C](f: java.util.function.BiFunction[A, B, C]): Unmarshaller[A, C] =
  //    asScala.mapWithInput { case (a, b) ⇒ f.apply(a, b) }
  //
  //  def flatMapWithInput[C](f: java.util.function.BiFunction[A, B, CompletionStage[C]]): Unmarshaller[A, C] =
  //    asScala.flatMapWithInput { case (a, b) ⇒ f.apply(a, b).toScala }
}
