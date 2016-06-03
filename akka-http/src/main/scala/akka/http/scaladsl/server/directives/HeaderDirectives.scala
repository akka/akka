/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.impl.util._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ HttpOriginRange, ModeledCustomHeader, ModeledCustomHeaderCompanion, Origin }

import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
 * @groupname header Header directives
 * @groupprio header 110
 */
trait HeaderDirectives {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Checks that request comes from the same origin. Extracts the [[Origin]] header value and verifies that
   * allowed range contains the obtained value. In the case of absent of the [[Origin]] header rejects
   * with [[MissingHeaderRejection]]. If the origin value is not in the allowed range
   * rejects with an [[InvalidOriginRejection]] and [[StatusCodes.Forbidden]] status.
   *
   * @group header
   */
  def checkSameOrigin(allowed: HttpOriginRange): Directive0 = {
    headerValueByType[Origin]().flatMap { origin ⇒
      if (origin.origins.exists(allowed.matches)) pass
      else reject(InvalidOriginRejection(origin.origins))
    }
  }

  /**
   * Extracts an HTTP header value using the given function. If the function result is undefined for all headers the
   * request is rejected with an empty rejection set. If the given function throws an exception the request is rejected
   * with a [[akka.http.scaladsl.server.MalformedHeaderRejection]].
   *
   * @group header
   */
  def headerValue[T](f: HttpHeader ⇒ Option[T]): Directive1[T] = {
    val protectedF: HttpHeader ⇒ Option[Either[Rejection, T]] = header ⇒
      try f(header).map(Right.apply)
      catch {
        case NonFatal(e) ⇒ Some(Left(MalformedHeaderRejection(header.name, e.getMessage.nullAsEmpty, Some(e))))
      }

    extract(_.request.headers.collectFirst(Function.unlift(protectedF))).flatMap {
      case Some(Right(a))        ⇒ provide(a)
      case Some(Left(rejection)) ⇒ reject(rejection)
      case None                  ⇒ reject
    }
  }

  /**
   * Extracts an HTTP header value using the given partial function. If the function is undefined for all headers the
   * request is rejected with an empty rejection set.
   *
   * @group header
   */
  def headerValuePF[T](pf: PartialFunction[HttpHeader, T]): Directive1[T] = headerValue(pf.lift)

  /**
   * Extracts the value of the first HTTP request header with the given name.
   * If no header with a matching name is found the request is rejected with a [[akka.http.scaladsl.server.MissingHeaderRejection]].
   *
   * @group header
   */
  def headerValueByName(headerName: Symbol): Directive1[String] = headerValueByName(headerName.name)

  /**
   * Extracts the value of the HTTP request header with the given name.
   * If no header with a matching name is found the request is rejected with a [[akka.http.scaladsl.server.MissingHeaderRejection]].
   *
   * @group header
   */
  def headerValueByName(headerName: String): Directive1[String] =
    headerValue(optionalValue(headerName.toLowerCase)) | reject(MissingHeaderRejection(headerName))

  /**
   * Extracts the first HTTP request header of the given type.
   * If no header with a matching type is found the request is rejected with a [[akka.http.scaladsl.server.MissingHeaderRejection]].
   *
   * Custom headers will only be matched by this directive if they extend [[ModeledCustomHeader]]
   * and provide a companion extending [[ModeledCustomHeaderCompanion]].
   *
   * @group header
   */
  def headerValueByType[T](magnet: HeaderMagnet[T]): Directive1[T] =
    headerValuePF(magnet.extractPF) | reject(MissingHeaderRejection(magnet.runtimeClass.getSimpleName))

  //#optional-header
  /**
   * Extracts an optional HTTP header value using the given function.
   * If the given function throws an exception the request is rejected
   * with a [[akka.http.scaladsl.server.MalformedHeaderRejection]].
   *
   * @group header
   */
  def optionalHeaderValue[T](f: HttpHeader ⇒ Option[T]): Directive1[Option[T]] =
    headerValue(f).map(Some(_): Option[T]).recoverPF {
      case Nil ⇒ provide(None)
    }
  //#

  /**
   * Extracts an optional HTTP header value using the given partial function.
   * If the given function throws an exception the request is rejected
   * with a [[akka.http.scaladsl.server.MalformedHeaderRejection]].
   *
   * @group header
   */
  def optionalHeaderValuePF[T](pf: PartialFunction[HttpHeader, T]): Directive1[Option[T]] =
    optionalHeaderValue(pf.lift)

  /**
   * Extracts the value of the optional HTTP request header with the given name.
   *
   * @group header
   */
  def optionalHeaderValueByName(headerName: Symbol): Directive1[Option[String]] =
    optionalHeaderValueByName(headerName.name)

  /**
   * Extracts the value of the optional HTTP request header with the given name.
   *
   * @group header
   */
  def optionalHeaderValueByName(headerName: String): Directive1[Option[String]] = {
    val lowerCaseName = headerName.toLowerCase
    extract(_.request.headers.collectFirst {
      case HttpHeader(`lowerCaseName`, value) ⇒ value
    })
  }

  /**
   * Extract the header value of the optional HTTP request header with the given type.
   *
   * Custom headers will only be matched by this directive if they extend [[ModeledCustomHeader]]
   * and provide a companion extending [[ModeledCustomHeaderCompanion]].
   *
   * @group header
   */
  def optionalHeaderValueByType[T <: HttpHeader](magnet: HeaderMagnet[T]): Directive1[Option[T]] =
    optionalHeaderValuePF(magnet.extractPF)

  private def optionalValue(lowerCaseName: String): HttpHeader ⇒ Option[String] = {
    case HttpHeader(`lowerCaseName`, value) ⇒ Some(value)
    case _                                  ⇒ None
  }
}

object HeaderDirectives extends HeaderDirectives

trait HeaderMagnet[T] {
  def classTag: ClassTag[T]
  def runtimeClass: Class[T]

  /**
   * Returns a partial function that checks if the input value is of runtime type
   * T and returns the value if it does. Doesn't take erased information into account.
   */
  def extractPF: PartialFunction[HttpHeader, T]
}
object HeaderMagnet extends LowPriorityHeaderMagnetImplicits {

  /**
   * If possible we want to apply the special logic for [[ModeledCustomHeader]] to extract custom headers by type,
   * otherwise the default `fromUnit` is good enough (for headers that the parser emits in the right type already).
   */
  implicit def fromUnitForModeledCustomHeader[T <: ModeledCustomHeader[T], H <: ModeledCustomHeaderCompanion[T]](u: Unit)(implicit tag: ClassTag[T], companion: ModeledCustomHeaderCompanion[T]): HeaderMagnet[T] =
    fromClassTagForModeledCustomHeader[T, H](tag, companion)

  implicit def fromClassForModeledCustomHeader[T <: ModeledCustomHeader[T], H <: ModeledCustomHeaderCompanion[T]](clazz: Class[T], companion: ModeledCustomHeaderCompanion[T]): HeaderMagnet[T] =
    fromClassTagForModeledCustomHeader(ClassTag(clazz), companion)

  implicit def fromClassTagForModeledCustomHeader[T <: ModeledCustomHeader[T], H <: ModeledCustomHeaderCompanion[T]](tag: ClassTag[T], companion: ModeledCustomHeaderCompanion[T]): HeaderMagnet[T] =
    new HeaderMagnet[T] {
      override def runtimeClass = tag.runtimeClass.asInstanceOf[Class[T]]
      override def classTag = tag
      override def extractPF = {
        case h if h.is(companion.lowercaseName) ⇒ companion.apply(h.value)
      }
    }

}

trait LowPriorityHeaderMagnetImplicits {
  implicit def fromClassNormalHeader[T <: HttpHeader](clazz: Class[T]): HeaderMagnet[T] =
    fromClassTagNormalHeader(ClassTag(clazz))

  // TODO DRY?
  implicit def fromClassNormalJavaHeader[T <: akka.http.javadsl.model.HttpHeader](clazz: Class[T]): HeaderMagnet[T] =
    new HeaderMagnet[T] {
      override def classTag: ClassTag[T] = ClassTag(clazz)
      override def runtimeClass: Class[T] = clazz
      override def extractPF: PartialFunction[HttpHeader, T] = { case x if runtimeClass.isAssignableFrom(x.getClass) ⇒ x.asInstanceOf[T] }
    }

  implicit def fromUnitNormalHeader[T <: HttpHeader](u: Unit)(implicit tag: ClassTag[T]): HeaderMagnet[T] =
    fromClassTagNormalHeader(tag)

  implicit def fromClassTagNormalHeader[T <: HttpHeader](tag: ClassTag[T]): HeaderMagnet[T] =
    new HeaderMagnet[T] {
      val classTag: ClassTag[T] = tag
      val runtimeClass: Class[T] = tag.runtimeClass.asInstanceOf[Class[T]]
      val extractPF: PartialFunction[Any, T] = { case x if runtimeClass.isAssignableFrom(x.getClass) ⇒ x.asInstanceOf[T] }
    }
}
