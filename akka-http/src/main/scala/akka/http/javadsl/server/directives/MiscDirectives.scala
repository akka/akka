/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.lang.{ Iterable ⇒ JIterable }
import java.util.function.BooleanSupplier
import java.util.function.{ Function ⇒ JFunction }
import java.util.function.Supplier

import scala.collection.JavaConverters._

import akka.http.javadsl.model.RemoteAddress
import akka.http.javadsl.model.headers.Language
import akka.http.javadsl.server.JavaScalaTypeEquivalence._

import akka.http.scaladsl.server.{ Directives ⇒ D }

abstract class MiscDirectives extends MethodDirectives {

  /**
   * Checks the given condition before running its inner route.
   * If the condition fails the route is rejected with a [[ValidationRejection]].
   */
  def validate(check: BooleanSupplier, errorMsg: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.validate(check.getAsBoolean(), errorMsg) { inner.get.toScala }
  }

  /**
   * Extracts the client's IP from either the X-Forwarded-For, Remote-Address or X-Real-IP header
   * (in that order of priority).
   */
  def extractClientIP(inner: JFunction[RemoteAddress, Route]): Route = ScalaRoute {
    D.extractClientIP { ip ⇒ inner.apply(ip).toScala }
  }

  /**
   * Rejects if the request entity is non-empty.
   */
  def requestEntityEmpty(inner: Supplier[Route]): Route = ScalaRoute {
    D.requestEntityEmpty { inner.get.toScala }
  }

  /**
   * Rejects with a [[RequestEntityExpectedRejection]] if the request entity is empty.
   * Non-empty requests are passed on unchanged to the inner route.
   */
  def requestEntityPresent(inner: Supplier[Route]): Route = ScalaRoute {
    D.requestEntityPresent { inner.get.toScala }
  }

  /**
   * Converts responses with an empty entity into (empty) rejections.
   * This way you can, for example, have the marshalling of a ''None'' option
   * be treated as if the request could not be matched.
   */
  def rejectEmptyResponse(inner: Supplier[Route]): Route = ScalaRoute {
    D.rejectEmptyResponse { inner.get.toScala }
  }

  /**
   * Inspects the request's `Accept-Language` header and determines,
   * which of the given language alternatives is preferred by the client.
   * (See http://tools.ietf.org/html/rfc7231#section-5.3.5 for more details on the
   * negotiation logic.)
   * If there are several best language alternatives that the client
   * has equal preference for (even if this preference is zero!)
   * the order of the arguments is used as a tie breaker (First one wins).
   *
   * If [languages] is empty, the route is rejected.
   */
  def selectPreferredLanguage(languages: JIterable[Language], inner: JFunction[Language, Route]): Route = ScalaRoute {
    languages.asScala.toList match {
      case head :: tail ⇒
        D.selectPreferredLanguage(head, tail.toSeq: _*) { lang ⇒ inner.apply(lang).toScala }
      case _ ⇒
        D.reject()
    }
  }

}
