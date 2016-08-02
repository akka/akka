/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl.server.directives

import java.util.function.{ Function ⇒ JFunction }
import java.util.{ List ⇒ JList, Map ⇒ JMap }

import akka.NotUsed
import akka.http.javadsl.common.EntityStreamingSupport
import akka.http.javadsl.marshalling.Marshaller
import akka.http.javadsl.model.{ HttpEntity, _ }
import akka.http.javadsl.server.Route
import akka.http.javadsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.marshalling.{ Marshalling, ToByteStringMarshaller, ToResponseMarshallable }
import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.stream.javadsl.Source
import akka.util.ByteString

/** EXPERIMENTAL API */
abstract class FramedEntityStreamingDirectives extends TimeoutDirectives {

  import akka.http.javadsl.server.RoutingJavaMapping._
  import akka.http.javadsl.server.RoutingJavaMapping.Implicits._

  @CorrespondsTo("asSourceOf")
  def entityAsSourceOf[T](um: Unmarshaller[ByteString, T], support: EntityStreamingSupport,
                          inner: java.util.function.Function[Source[T, NotUsed], Route]): Route = RouteAdapter {
    val umm = D.asSourceOf(um.asScala, support.asScala)
    D.entity(umm) { s: akka.stream.scaladsl.Source[T, NotUsed] ⇒
      inner(s.asJava).delegate
    }
  }

  // implicits and multiple parameter lists used internally, Java caller does not benefit or use it
  @CorrespondsTo("complete")
  def completeWithSource[T, M](source: Source[T, M])(implicit m: Marshaller[T, ByteString], support: EntityStreamingSupport): Route = RouteAdapter {
    import akka.http.scaladsl.marshalling.PredefinedToResponseMarshallers._
    val mm = m.map(ByteStringAsEntityFn).asScalaCastOutput[akka.http.scaladsl.model.RequestEntity]
    val mmm = fromEntityStreamingSupportAndEntityMarshaller[T, M](support.asScala, mm)
    val response = ToResponseMarshallable(source.asScala)(mmm)
    D.complete(response)
  }

  // implicits and multiple parameter lists used internally, Java caller does not benefit or use it
  @CorrespondsTo("complete")
  def completeOKWithSource[T, M](source: Source[T, M])(implicit m: Marshaller[T, RequestEntity], support: EntityStreamingSupport): Route = RouteAdapter {
    import akka.http.scaladsl.marshalling.PredefinedToResponseMarshallers._
    // don't try this at home:
    val mm = m.asScalaCastOutput[akka.http.scaladsl.model.RequestEntity].map(_.httpEntity.asInstanceOf[akka.http.scaladsl.model.RequestEntity])
    implicit val mmm = fromEntityStreamingSupportAndEntityMarshaller[T, M](support.asScala, mm)
    val response = ToResponseMarshallable(source.asScala)
    D.complete(response)
  }

  private[this] val ByteStringAsEntityFn = new java.util.function.Function[ByteString, HttpEntity]() {
    override def apply(bs: ByteString): HttpEntity = HttpEntities.create(bs)
  }
}

object FramedEntityStreamingDirectives extends FramedEntityStreamingDirectives
