/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl.server.directives

import akka.http.javadsl.model.{ ContentType, HttpEntity }
import akka.util.ByteString
import java.util.{ List ⇒ JList, Map ⇒ JMap }
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Optional
import java.util.function.{ Function ⇒ JFunction }

import akka.NotUsed

import scala.collection.JavaConverters._
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.common.{ FramingWithContentType, SourceRenderingMode }
import akka.http.javadsl.server.{ Marshaller, Route, Unmarshaller }
import akka.http.javadsl.model._
import akka.http.scaladsl.marshalling.{ ToResponseMarshallable, ToResponseMarshaller }
import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.http.scaladsl.unmarshalling
import akka.stream.javadsl.Source

/** EXPERIMENTAL API */
abstract class FramedEntityStreamingDirectives extends TimeoutDirectives {
  // important import, as we implicitly resolve some marshallers inside the below directives
  import akka.http.scaladsl.server.directives.FramedEntityStreamingDirectives._

  @CorrespondsTo("asSourceOf")
  def entityasSourceOf[T](um: Unmarshaller[HttpEntity, T], framing: FramingWithContentType,
                          inner: java.util.function.Function[Source[T, NotUsed], Route]): Route = RouteAdapter {
    val sum = um.asScalaCastInput[akka.http.scaladsl.model.HttpEntity]
    D.entity(D.asSourceOf[T](framing.asScala)(sum)) { s: akka.stream.scaladsl.Source[T, NotUsed] ⇒
      inner(s.asJava).delegate
    }
  }

  @CorrespondsTo("asSourceOfAsync")
  def entityAsSourceAsyncOf[T](
    parallelism: Int,
    um:          Unmarshaller[HttpEntity, T], framing: FramingWithContentType,
    inner: java.util.function.Function[Source[T, NotUsed], Route]): Route = RouteAdapter {
    val sum = um.asScalaCastInput[akka.http.scaladsl.model.HttpEntity]
    D.entity(D.asSourceOfAsync[T](parallelism, framing.asScala)(sum)) { s: akka.stream.scaladsl.Source[T, NotUsed] ⇒
      inner(s.asJava).delegate
    }
  }

  @CorrespondsTo("asSourceOfAsyncUnordered")
  def entityAsSourceAsyncUnorderedOf[T](
    parallelism: Int,
    um:          Unmarshaller[HttpEntity, T], framing: FramingWithContentType,
    inner: java.util.function.Function[Source[T, NotUsed], Route]): Route = RouteAdapter {
    val sum = um.asScalaCastInput[akka.http.scaladsl.model.HttpEntity]
    D.entity(D.asSourceOfAsyncUnordered[T](parallelism, framing.asScala)(sum)) { s: akka.stream.scaladsl.Source[T, NotUsed] ⇒
      inner(s.asJava).delegate
    }
  }

  // implicit used internally, Java caller does not benefit or use it
  @CorrespondsTo("complete")
  def completeWithSource[T, M](implicit source: Source[T, M], m: Marshaller[T, ByteString], rendering: SourceRenderingMode): Route = RouteAdapter {
    import akka.http.scaladsl.marshalling.PredefinedToResponseMarshallers._
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
    import akka.http.javadsl.server.RoutingJavaMapping._
    import akka.http.javadsl.server.RoutingJavaMapping.Implicits._
    val mm = m.asScalaCastOutput
    D._sourceMarshaller[T, M](mm, rendering.asScala).compose({ h: akka.stream.javadsl.Source[T, M] ⇒ h.asScala })
  }

  private[this] val ByteStringAsEntityFn = new java.util.function.Function[ByteString, HttpEntity]() {
    override def apply(bs: ByteString): HttpEntity = HttpEntities.create(bs)
  }
}

object FramedEntityStreamingDirectives extends FramedEntityStreamingDirectives
