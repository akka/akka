/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.util.Optional
import java.util.{function => jf}

import scala.compat.java8.OptionConverters._
import scala.reflect.ClassTag

import akka.http.javadsl.model.HttpHeader
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.directives.{HeaderDirectives => D}
import akka.http.scaladsl.server.util.ClassMagnet

/**
 * 
 * 
 * FIXME When implementing custom headers in Java, just extend akka.http.scaladsl.model.headers.CustomHeader,
 * since the java API's CustomHeader class does not implement render().
 */
abstract class HeaderDirectives extends FutureDirectives {
  /**
   * Extracts an HTTP header value using the given function. If the function result is undefined for all headers the
   * request is rejected with an empty rejection set. If the given function throws an exception the request is rejected
   * with a [[spray.routing.MalformedHeaderRejection]].
   */
  def headerValue[T](f: jf.Function[HttpHeader, Optional[T]], inner: jf.Function[T, Route]) = ScalaRoute {
    D.headerValue(h => f.apply(h).asScala) { value => 
      inner.apply(value).toScala
    }
  }
  
  /**
   * Extracts an HTTP header value using the given partial function. If the function is undefined for all headers the
   * request is rejected with an empty rejection set.
   */
  def headerValuePF[T](pf: PartialFunction[HttpHeader, T], inner: jf.Function[T, Route]) = ScalaRoute {
    D.headerValuePF(pf) { value =>
      inner.apply(value).toScala
    }
  }
  
  /**
   * Extracts the value of the first HTTP request header with the given name.
   * If no header with a matching name is found the request is rejected with a [[spray.routing.MissingHeaderRejection]].
   */
  def headerValueByName(headerName: String, inner: jf.Function[String, Route]) = ScalaRoute {
    D.headerValueByName(headerName) { value =>
      inner.apply(value).toScala
    }
  }
  
  /**
   * Extracts the first HTTP request header of the given type.
   * If no header with a matching type is found the request is rejected with a [[spray.routing.MissingHeaderRejection]].
   */
  def headerValueByType[T <: HttpHeader](t: Class[T], inner: jf.Function[T, Route]) = ScalaRoute {
    D.headerValueByType(ClassMagnet(ClassTag(t)).asInstanceOf[ClassMagnet[akka.http.scaladsl.model.HttpHeader]]) { value =>
      inner.apply(value.asInstanceOf[T]).toScala
    }
  }
  
  /**
   * Extracts an optional HTTP header value using the given function.
   * If the given function throws an exception the request is rejected
   * with a [[spray.routing.MalformedHeaderRejection]].
   */
  def optionalHeaderValue[T](f: jf.Function[HttpHeader, Optional[T]], inner: jf.Function[Optional[T], Route]) = ScalaRoute {
    D.optionalHeaderValue(h => f.apply(h).asScala) { value => 
      inner.apply(value.asJava).toScala
    }
  }
  
  /**
   * Extracts an optional HTTP header value using the given partial function.
   * If the given function throws an exception the request is rejected
   * with a [[spray.routing.MalformedHeaderRejection]].
   */
  def optionalHeaderValuePF[T](pf: PartialFunction[HttpHeader, T], inner: jf.Function[Optional[T], Route]) = ScalaRoute {
    D.optionalHeaderValuePF(pf) { value =>
      inner.apply(value.asJava).toScala
    }
  }
  
  /**
   * Extracts the value of the optional HTTP request header with the given name.
   */
  def optionalHeaderValueByName(headerName: String, inner: jf.Function[Optional[String], Route]) = ScalaRoute {
    D.optionalHeaderValueByName(headerName) { value =>
      inner.apply(value.asJava).toScala
    }
  }
  
  /**
   * Extract the header value of the optional HTTP request header with the given type.
   */
  def optionalHeaderValueByType[T <: HttpHeader](t: Class[T], inner: jf.Function[Optional[T], Route]) = ScalaRoute {
    D.optionalHeaderValueByType(ClassMagnet(ClassTag(t)).asInstanceOf[ClassMagnet[akka.http.scaladsl.model.HttpHeader]]) { value =>
      inner.apply(value.asInstanceOf[Optional[T]]).toScala
    }
  }
  
}
