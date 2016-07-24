/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl.server.directives

import java.util.function.{ Function ⇒ JFunction }
import java.util.{ List ⇒ JList, Map ⇒ JMap }

import akka.NotUsed
import akka.http.javadsl.common.{ FramingWithContentType, SourceRenderingMode }
import akka.http.javadsl.model.{ HttpEntity, _ }
import akka.http.javadsl.server.{ Marshaller, Route, Unmarshaller }
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.stream.javadsl.Source
import akka.util.ByteString

/** EXPERIMENTAL API */
abstract class FramedEntityStreamingDirectives extends TimeoutDirectives {

  @CorrespondsTo("asSourceOf")
  def entityasSourceOf[T](um: Unmarshaller[ByteString, T], framing: FramingWithContentType,
                          inner: java.util.function.Function[Source[T, NotUsed], Route]): Route = RouteAdapter {
    D.entity(D.asSourceOf[T](framing.asScala)(um.asScala)) { s: akka.stream.scaladsl.Source[T, NotUsed] ⇒
      inner(s.asJava).delegate
    }
  }

  @CorrespondsTo("asSourceOfAsync")
  def entityAsSourceAsyncOf[T](
    parallelism: Int,
    um:          Unmarshaller[ByteString, T], framing: FramingWithContentType,
    inner: java.util.function.Function[Source[T, NotUsed], Route]): Route = RouteAdapter {
    D.entity(D.asSourceOfAsync[T](parallelism, framing.asScala)(um.asScala)) { s: akka.stream.scaladsl.Source[T, NotUsed] ⇒
      inner(s.asJava).delegate
    }
  }

  @CorrespondsTo("asSourceOfAsyncUnordered")
  def entityAsSourceAsyncUnorderedOf[T](
    parallelism: Int,
    um:          Unmarshaller[ByteString, T], framing: FramingWithContentType,
    inner: java.util.function.Function[Source[T, NotUsed], Route]): Route = RouteAdapter {
    D.entity(D.asSourceOfAsyncUnordered[T](parallelism, framing.asScala)(um.asScala)) { s: akka.stream.scaladsl.Source[T, NotUsed] ⇒
      inner(s.asJava).delegate
    }
  }

  // implicits used internally, Java caller does not benefit or use it
  @CorrespondsTo("complete")
  def completeWithSource[T, M](implicit source: Source[T, M], m: Marshaller[T, ByteString], rendering: SourceRenderingMode): Route = RouteAdapter {
    implicit val mm = _sourceMarshaller(m.map(ByteStringAsEntityFn), rendering)
    val response = ToResponseMarshallable(source)
    D.complete(response)
  }

  @CorrespondsTo("complete")
  def completeOKWithSource[T, M](implicit source: Source[T, M], m: Marshaller[T, RequestEntity], rendering: SourceRenderingMode): Route = RouteAdapter {
    implicit val mm = _sourceMarshaller[T, M](m, rendering)
    val response = ToResponseMarshallable(source)
    D.complete(response)
  }

  implicit private def _sourceMarshaller[T, M](implicit m: Marshaller[T, HttpEntity], rendering: SourceRenderingMode) = {
    import akka.http.javadsl.server.RoutingJavaMapping.Implicits._
    import akka.http.javadsl.server.RoutingJavaMapping._
    val mm = m.asScalaCastOutput
    D._sourceMarshaller[T, M](mm, rendering.asScala).compose({ h: akka.stream.javadsl.Source[T, M] ⇒ h.asScala })
  }

  private[this] val ByteStringAsEntityFn = new java.util.function.Function[ByteString, HttpEntity]() {
    override def apply(bs: ByteString): HttpEntity = HttpEntities.create(bs)
  }
}

object FramedEntityStreamingDirectives extends FramedEntityStreamingDirectives
