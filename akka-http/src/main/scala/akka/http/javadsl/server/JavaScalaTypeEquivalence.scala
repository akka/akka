/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server

import akka.http.javadsl
import akka.http.scaladsl
import scala.language.implicitConversions
import scala.reflect.ClassTag
import java.util.Optional

object JavaScalaTypeEquivalence {
  // We define implicit conversions from the javadsl model objects to the scaladsl subclasses,
  // for all types where there is only 1 valid direct subclass of the javadsl type
  // (being the scaladsl one)

  // These are gathered here rather than doing explicit asInstanceOf throughout the code, in order
  // to get predictable compile errors if this hierarchy might ever change.

  implicit def assumeScala[T, J, S](o: scaladsl.marshalling.Marshaller[T, J])(implicit c: TypeEquivalent[J, S]) =
    o.asInstanceOf[scaladsl.marshalling.Marshaller[T, S]]
  implicit def assumeJava[J, S, T](o: scaladsl.unmarshalling.Unmarshaller[S, T])(implicit c: TypeEquivalent[J, S]) =
    o.asInstanceOf[scaladsl.unmarshalling.Unmarshaller[J, T]]
  implicit def assumeScala[J, S, T](o: scaladsl.unmarshalling.Unmarshaller[J, T])(implicit c: TypeEquivalent[J, S]) =
    o.asInstanceOf[scaladsl.unmarshalling.Unmarshaller[S, T]]
  implicit def assumeScala[J, S](j: J)(implicit c: TypeEquivalent[J, S]): S =
    j.asInstanceOf[S]
  implicit def assumeScala[J, S](seq: Seq[J])(implicit c: TypeEquivalent[J, S]): Seq[S] =
    seq.asInstanceOf[Seq[S]]
  implicit def assumeScala[J, S](set: Set[J])(implicit c: TypeEquivalent[J, S]): Set[S] =
    set.asInstanceOf[Set[S]]
  implicit def assumeScala[J, S](seq: collection.immutable.Seq[J])(implicit c: TypeEquivalent[J, S]): collection.immutable.Seq[S] =
    seq.asInstanceOf[collection.immutable.Seq[S]]
  implicit def assumeJavaFlow[JI, SI, JO, SO, M](flow: akka.stream.scaladsl.Flow[SI, SO, M])(implicit c1: TypeEquivalent[JI, SI], c2: TypeEquivalent[JO, SO]) =
    flow.asInstanceOf[akka.stream.scaladsl.Flow[JI, JO, M]]
  implicit def assumeScalaFlow[JI, SI, JO, SO, M](flow: akka.stream.javadsl.Flow[JI, JO, M])(implicit c1: TypeEquivalent[JI, SI], c2: TypeEquivalent[JO, SO]) =
    flow.asInstanceOf[akka.stream.scaladsl.Flow[SI, SO, M]]
  implicit def assumeScala[J, S](tag: ClassTag[J])(implicit c: TypeEquivalent[J, S]): ClassTag[S] =
    tag.asInstanceOf[ClassTag[S]]
  implicit def assumeJava[J, S](o: Optional[S])(implicit c: TypeEquivalent[J, S]): Optional[J] =
    o.asInstanceOf[Optional[J]]
  implicit def assumeScala[J, S](o: Option[J])(implicit c: TypeEquivalent[J, S]): Option[S] =
    o.asInstanceOf[Option[S]]

  /**
   * Marker class to indicate that either
   * - [J] has only one direct subclass, being [S]
   * OR
   * - all Java DSL subtypes of [J] have scala DSL subclasses that are also subclasses of [S]
   */
  case class TypeEquivalent[-J, S] private[JavaScalaTypeEquivalence] ()
  implicit val javaToScalaMediaType = TypeEquivalent[javadsl.model.MediaType, scaladsl.model.MediaType]
  implicit val javaToScalaContentType = TypeEquivalent[javadsl.model.ContentType, scaladsl.model.ContentType]
  implicit val javaToScalaHttpMethod = TypeEquivalent[javadsl.model.HttpMethod, scaladsl.model.HttpMethod]
  implicit val javaToScalaHttpRequest = TypeEquivalent[javadsl.model.HttpRequest, scaladsl.model.HttpRequest]
  implicit val javaToScalaRequestEntity = TypeEquivalent[javadsl.model.RequestEntity, scaladsl.model.RequestEntity]
  implicit val javaToScalaHttpResponse = TypeEquivalent[javadsl.model.HttpResponse, scaladsl.model.HttpResponse]
  implicit val javaToScalaStatusCode = TypeEquivalent[javadsl.model.StatusCode, scaladsl.model.StatusCode]
  implicit val javaToScalaHttpEncoding = TypeEquivalent[javadsl.model.headers.HttpEncoding, scaladsl.model.headers.HttpEncoding]
  implicit val javaToScalaByteRange = TypeEquivalent[javadsl.model.headers.ByteRange, scaladsl.model.headers.ByteRange]
  implicit val javaToScalaHttpChallenge = TypeEquivalent[javadsl.model.headers.HttpChallenge, scaladsl.model.headers.HttpChallenge]
  implicit val javaToScalaHttpHeader = TypeEquivalent[javadsl.model.HttpHeader, scaladsl.model.HttpHeader]
  implicit val javaToScalaLanguage = TypeEquivalent[javadsl.model.headers.Language, scaladsl.model.headers.Language]
  implicit val javaToScalaHttpCookiePair = TypeEquivalent[javadsl.model.headers.HttpCookiePair, scaladsl.model.headers.HttpCookiePair]
  implicit val javaToScalaHttpCookie = TypeEquivalent[javadsl.model.headers.HttpCookie, scaladsl.model.headers.HttpCookie]
  implicit val javaToScalaHttpCredentials = TypeEquivalent[javadsl.model.headers.HttpCredentials, scaladsl.model.headers.HttpCredentials]
  implicit val javaToScalaMessage = TypeEquivalent[javadsl.model.ws.Message, scaladsl.model.ws.Message]
  implicit val javaToScalaEntityTag = TypeEquivalent[javadsl.model.headers.EntityTag, scaladsl.model.headers.EntityTag]
  implicit val javaToScalaDateTime = TypeEquivalent[javadsl.model.DateTime, scaladsl.model.DateTime]
  implicit val javaToScalaRouteSettings = TypeEquivalent[javadsl.settings.RoutingSettings, scaladsl.settings.RoutingSettings]
  implicit val javaToScalaLogEntry = TypeEquivalent[javadsl.server.directives.LogEntry, scaladsl.server.directives.LogEntry]

  // not made implicit since these are subtypes of RequestEntity
  val javaToScalaHttpEntity = TypeEquivalent[javadsl.model.HttpEntity, scaladsl.model.HttpEntity]
  val javaToScalaResponseEntity = TypeEquivalent[javadsl.model.ResponseEntity, scaladsl.model.ResponseEntity]
}
