/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import headers._

trait MiscDirectives {
  import RouteDirectives._

  /**
   * Checks the given condition before running its inner route.
   * If the condition fails the route is rejected with a [[ValidationRejection]].
   */
  def validate(check: ⇒ Boolean, errorMsg: String): Directive0 =
    Directive { inner ⇒ if (check) inner(()) else reject(ValidationRejection(errorMsg)) }

  /**
   * Extracts the client's IP from either the X-Forwarded-For, Remote-Address or X-Real-IP header
   * (in that order of priority).
   */
  def extractClientIP: Directive1[RemoteAddress] = MiscDirectives._extractClientIP

  /**
   * Rejects if the request entity is non-empty.
   */
  def requestEntityEmpty: Directive0 = MiscDirectives._requestEntityEmpty

  /**
   * Rejects with a [[RequestEntityExpectedRejection]] if the request entity is empty.
   * Non-empty requests are passed on unchanged to the inner route.
   */
  def requestEntityPresent: Directive0 = MiscDirectives._requestEntityPresent

  /**
   * Converts responses with an empty entity into (empty) rejections.
   * This way you can, for example, have the marshalling of a ''None'' option
   * be treated as if the request could not be matched.
   */
  def rejectEmptyResponse: Directive0 = MiscDirectives._rejectEmptyResponse

  /**
   * Inspects the request's `Accept-Language` header and determines,
   * which of the given language alternatives is preferred by the client.
   * (See http://tools.ietf.org/html/rfc7231#section-5.3.5 for more details on the
   * negotiation logic.)
   * If there are several best language alternatives that the client
   * has equal preference for (even if this preference is zero!)
   * the order of the arguments is used as a tie breaker (First one wins).
   */
  def selectPreferredLanguage(first: Language, more: Language*): Directive1[Language] = {
    val available = first :: List(more: _*) // we use List rather than Seq since element count is likely very small
    BasicDirectives.extractRequest.map { req ⇒
      val sortedWithQValues = available.zip(available.map(req.qValueForLanguage(_))).sortBy(-_._2)
      val firstBest = sortedWithQValues.head
      val moreBest = sortedWithQValues.tail.takeWhile(_._2 == firstBest._2)
      if (moreBest.nonEmpty) {
        // we have several languages that have the same qvalue, so we pick the one the `Accept-Header` lists first
        val allBest = firstBest :: moreBest
        req.acceptedLanguageRanges.flatMap(range ⇒ allBest.find(t ⇒ range.matches(t._1))).head._1
      } else firstBest._1 // we have a single best match, so pick that
    }
  }
}

object MiscDirectives extends MiscDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import RouteDirectives._
  import RouteResult._

  private val _extractClientIP: Directive1[RemoteAddress] =
    headerValuePF { case `X-Forwarded-For`(Seq(address, _*)) ⇒ address } |
      headerValuePF { case `Remote-Address`(address) ⇒ address } |
      headerValuePF { case h if h.is("x-real-ip") ⇒ RemoteAddress(h.value) }

  private val _requestEntityEmpty: Directive0 =
    extract(_.request.entity.isKnownEmpty).flatMap(if (_) pass else reject)

  private val _requestEntityPresent: Directive0 =
    extract(_.request.entity.isKnownEmpty).flatMap(if (_) reject else pass)

  private val _rejectEmptyResponse: Directive0 =
    mapRouteResult {
      case Complete(response) if response.entity.isKnownEmpty ⇒ Rejected(Nil)
      case x ⇒ x
    }
}
