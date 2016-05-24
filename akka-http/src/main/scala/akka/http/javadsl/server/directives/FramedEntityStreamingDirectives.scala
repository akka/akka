/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl.server.directives

import akka.http.javadsl.model.{ContentType, HttpEntity}
import akka.util.ByteString
import java.util.{List => JList, Map => JMap}
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Optional
import java.util.function.{Function => JFunction}

import akka.NotUsed

import scala.collection.JavaConverters._
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.server.directives.FramedEntityStreamingDirectives.SourceRenderingMode
import akka.http.javadsl.server.{Route, Unmarshaller}
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.{FramingWithContentType, Directives => D}
import akka.http.scaladsl.server.directives.ParameterDirectives._
import akka.stream.javadsl.Source

import scala.compat.java8.OptionConverters

/** EXPERIMENTAL API */
trait FramedEntityStreamingDirectives {
  
  def entityAsStream[T](clazz: Class[T], um: Unmarshaller[_ >: HttpEntity, T], framing: FramingWithContentType, 
                        inner: java.util.function.Function[Source[T, NotUsed], Route]): Route = RouteAdapter {
    D.entity[T](D.stream[T](um, framing)) { s =>
      
    }
    ???
  }
  
  def completeWithSource[T](source: Source[T, Any], rendering: SourceRenderingMode): Route = RouteAdapter {
    val response = ToResponseMarshallable(source)
    D.complete(response)
  }
}

object FramedEntityStreamingDirectives extends FramedEntityStreamingDirectives {
  trait SourceRenderingMode {
    def getContentType: ContentType

    def start: ByteString
    def between: ByteString
    def end: ByteString
  }
}
