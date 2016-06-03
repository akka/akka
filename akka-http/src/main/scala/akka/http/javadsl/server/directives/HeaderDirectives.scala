/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.util.Optional
import java.util.{ function ⇒ jf }

import akka.actor.ReflectiveDynamicAccess

import scala.compat.java8.OptionConverters
import scala.compat.java8.OptionConverters._
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.model.{ HttpHeader, StatusCodes }
import akka.http.javadsl.model.headers.HttpOriginRange
import akka.http.javadsl.server.{ InvalidOriginRejection, MissingHeaderRejection, Route }
import akka.http.scaladsl.model.headers.{ ModeledCustomHeader, ModeledCustomHeaderCompanion, Origin }
import akka.http.scaladsl.server.directives.{ HeaderMagnet, BasicDirectives ⇒ B, HeaderDirectives ⇒ D }
import akka.stream.ActorMaterializer

import scala.reflect.ClassTag
import scala.util.{ Failure, Success }

abstract class HeaderDirectives extends FutureDirectives {

  private type ScalaHeaderMagnet = HeaderMagnet[akka.http.scaladsl.model.HttpHeader]

  /**
   * Checks that request comes from the same origin. Extracts the [[Origin]] header value and verifies that
   * allowed range contains the obtained value. In the case of absent of the [[Origin]] header rejects
   * with [[MissingHeaderRejection]]. If the origin value is not in the allowed range
   * rejects with an [[InvalidOriginRejection]] and [[StatusCodes.FORBIDDEN]] status.
   *
   * @group header
   */
  def checkSameOrigin(allowed: HttpOriginRange, inner: jf.Supplier[Route]): Route = RouteAdapter {
    D.checkSameOrigin(allowed.asScala) { inner.get().delegate }
  }

  /**
   * Extracts an HTTP header value using the given function. If the function result is undefined for all headers the
   * request is rejected with an empty rejection set. If the given function throws an exception the request is rejected
   * with a [[akka.http.javadsl.server.MalformedHeaderRejection]].
   */
  def headerValue[T](f: jf.Function[HttpHeader, Optional[T]], inner: jf.Function[T, Route]) = RouteAdapter {
    D.headerValue(h ⇒ f.apply(h).asScala) { value ⇒
      inner.apply(value).delegate
    }
  }

  /**
   * Extracts an HTTP header value using the given partial function. If the function is undefined for all headers the
   * request is rejected with an empty rejection set.
   */
  def headerValuePF[T](pf: PartialFunction[HttpHeader, T], inner: jf.Function[T, Route]) = RouteAdapter {
    D.headerValuePF(pf) { value ⇒
      inner.apply(value).delegate
    }
  }

  /**
   * Extracts the value of the first HTTP request header with the given name.
   * If no header with a matching name is found the request is rejected with a [[akka.http.javadsl.server.MissingHeaderRejection]].
   */
  def headerValueByName(headerName: String, inner: jf.Function[String, Route]) = RouteAdapter {
    D.headerValueByName(headerName) { value ⇒
      inner.apply(value).delegate
    }
  }

  /**
   * Extracts the first HTTP request header of the given type.
   * If no header with a matching type is found the request is rejected with a [[akka.http.javadsl.server.MissingHeaderRejection]].
   */
  def headerValueByType[T <: HttpHeader](t: Class[T], inner: jf.Function[T, Route]) = RouteAdapter {

    def magnetForModeledCustomHeader(clazz: Class[T]): HeaderMagnet[T] = {
      // figure out the modeled header companion and use that to parse the header
      val refl = new ReflectiveDynamicAccess(getClass.getClassLoader)
      refl.getObjectFor[ModeledCustomHeaderCompanion[_]](t.getName) match {
        case Success(companion) ⇒
          new HeaderMagnet[T] {
            override def classTag = ClassTag(t)
            override def runtimeClass = t
            override def extractPF = {
              case h if h.is(companion.lowercaseName) ⇒ companion.apply(h.toString).asInstanceOf[T]
            }
          }
        case Failure(ex) ⇒ throw new RuntimeException(s"Failed to find or access the ModeledCustomHeaderCompanion for [${t.getName}]", ex)
      }
    }

    val magnet: HeaderMagnet[T] =
      if (classOf[ModeledCustomHeader[_]].isAssignableFrom(t)) magnetForModeledCustomHeader(t)
      else HeaderMagnet.fromClassNormalJavaHeader(t)

    D.headerValueByType(magnet) { value ⇒
      inner.apply(value).delegate
    }

  }

  /**
   * Extracts an optional HTTP header value using the given function.
   * If the given function throws an exception the request is rejected
   * with a [[akka.http.javadsl.server.MalformedHeaderRejection]].
   */
  def optionalHeaderValue[T](f: jf.Function[HttpHeader, Optional[T]], inner: jf.Function[Optional[T], Route]) = RouteAdapter {
    D.optionalHeaderValue(h ⇒ f.apply(h).asScala) { value ⇒
      inner.apply(value.asJava).delegate
    }
  }

  /**
   * Extracts an optional HTTP header value using the given partial function.
   * If the given function throws an exception the request is rejected
   * with a [[akka.http.javadsl.server.MalformedHeaderRejection]].
   */
  def optionalHeaderValuePF[T](pf: PartialFunction[HttpHeader, T], inner: jf.Function[Optional[T], Route]) = RouteAdapter {
    D.optionalHeaderValuePF(pf) { value ⇒
      inner.apply(value.asJava).delegate
    }
  }

  /**
   * Extracts the value of the optional HTTP request header with the given name.
   */
  def optionalHeaderValueByName(headerName: String, inner: jf.Function[Optional[String], Route]) = RouteAdapter {
    D.optionalHeaderValueByName(headerName) { value ⇒
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
    D.optionalHeaderValueByType(HeaderMagnet.fromClassNormalJavaHeader(t).asInstanceOf[ScalaHeaderMagnet]) { value ⇒
      val valueT = value.asInstanceOf[Option[T]] // we know this is safe because T <: HttpHeader
      inner.apply(OptionConverters.toJava[T](valueT)).delegate
    }
  }

}
