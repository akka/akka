/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.util.Optional
import java.util.{function => jf}

import scala.compat.java8.OptionConverters._        
import akka.http.impl.util.JavaMapping.Implicits._

import akka.http.javadsl.model.HttpHeader
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.directives.{HeaderDirectives => D, HeaderMagnet}
import akka.http.scaladsl.server.util.ClassMagnet

/**
 * FIXME When implementing custom headers in Java, just extend akka.http.scaladsl.model.headers.CustomHeader,
 * since the java API's CustomHeader class does not implement render().
 */
abstract class HeaderDirectives extends FutureDirectives {

  type ScalaHeaderMagnet = HeaderMagnet[akka.http.scaladsl.model.HttpHeader]

  /**
   * Extracts an HTTP header value using the given function. If the function result is undefined for all headers the
   * request is rejected with an empty rejection set. If the given function throws an exception the request is rejected
   * with a [[akka.http.javadsl.server.MalformedHeaderRejection]].
   */
  def headerValue[T](f: jf.Function[HttpHeader, Optional[T]], inner: jf.Function[T, Route]) = RouteAdapter {
    D.headerValue(h => f.apply(h).asScala) { value => 
      inner.apply(value).delegate
    }
  }
  
  /**
   * Extracts an HTTP header value using the given partial function. If the function is undefined for all headers the
   * request is rejected with an empty rejection set.
   */
  def headerValuePF[T](pf: PartialFunction[HttpHeader, T], inner: jf.Function[T, Route]) = RouteAdapter {
    D.headerValuePF(pf) { value =>
      inner.apply(value).delegate
    }
  }
  
  /**
   * Extracts the value of the first HTTP request header with the given name.
   * If no header with a matching name is found the request is rejected with a [[akka.http.javadsl.server.MissingHeaderRejection]].
   */
  def headerValueByName(headerName: String, inner: jf.Function[String, Route]) = RouteAdapter {
    D.headerValueByName(headerName) { value =>
      inner.apply(value).delegate
    }
  }

  /**
   * FIXME: WARNING: Custom headers don't work yet with this directive!
   *
   * Extracts the first HTTP request header of the given type.
   * If no header with a matching type is found the request is rejected with a [[akka.http.javadsl.server.MissingHeaderRejection]].
   */
  def headerValueByType[T <: HttpHeader](t: Class[T], inner: jf.Function[T, Route]) = RouteAdapter {
    // TODO custom headers don't work yet
    // TODO needs instance of check if it's a modeled header and then magically locate companion
    D.headerValueByType(HeaderMagnet.fromClassNormalJavaHeader(t)) { value =>
      inner.apply(value).delegate
    }
  }
  
  /**
   * Extracts an optional HTTP header value using the given function.
   * If the given function throws an exception the request is rejected
   * with a [[akka.http.javadsl.server.MalformedHeaderRejection]].
   */
  def optionalHeaderValue[T](f: jf.Function[HttpHeader, Optional[T]], inner: jf.Function[Optional[T], Route]) = RouteAdapter {
    D.optionalHeaderValue(h => f.apply(h).asScala) { value => 
      inner.apply(value.asJava).delegate
    }
  }
  
  /**
   * Extracts an optional HTTP header value using the given partial function.
   * If the given function throws an exception the request is rejected
   * with a [[akka.http.javadsl.server.MalformedHeaderRejection]].
   */
  def optionalHeaderValuePF[T](pf: PartialFunction[HttpHeader, T], inner: jf.Function[Optional[T], Route]) = RouteAdapter {
    D.optionalHeaderValuePF(pf) { value =>
      inner.apply(value.asJava).delegate
    }
  }
  
  /**
   * Extracts the value of the optional HTTP request header with the given name.
   */
  def optionalHeaderValueByName(headerName: String, inner: jf.Function[Optional[String], Route]) = RouteAdapter {
    D.optionalHeaderValueByName(headerName) { value =>
      inner.apply(value.asJava).delegate
    }
  }

  /**
   * FIXME: WARNING: Custom headers don't work yet with this directive!
   *
   * Extract the header value of the optional HTTP request header with the given type.
   */
  def optionalHeaderValueByType[T <: HttpHeader](t: Class[T], inner: jf.Function[Optional[T], Route]) = RouteAdapter {
    // TODO custom headers don't work yet
    // TODO needs instance of check if it's a modeled header and then magically locate companion
    D.optionalHeaderValueByType(HeaderMagnet.fromClassNormalJavaHeader(t).asInstanceOf[ScalaHeaderMagnet]) { value =>
      inner.apply(value.asInstanceOf[Optional[T]]).delegate
    }
  }
  
}
