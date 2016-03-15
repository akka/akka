/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.model
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import HttpCharsets.`UTF-8`

final class MediaTypeNegotiator(requestHeaders: Seq[HttpHeader]) {

  /**
   * The media-ranges accepted by the client according to the given request headers, sorted by
   * 1. increasing generality (i.e. most specific first)
   * 2. decreasing q-value (only for ranges targeting a single MediaType)
   * 3. order of appearance in the `Accept` header(s)
   */
  val acceptedMediaRanges: List[MediaRange] =
    (for {
      Accept(mediaRanges) ← requestHeaders
      range ← mediaRanges
    } yield range).sortBy { // `sortBy` is stable, i.e. upholds the original order on identical keys
      case x if x.isWildcard     ⇒ 2f // most general, needs to come last
      case MediaRange.One(_, qv) ⇒ -qv // most specific, needs to come first
      case _                     ⇒ 1f // simple range like `image/*`
    }.toList

  /**
   * Returns the q-value that the client (implicitly or explicitly) attaches to the given media-type.
   * See http://tools.ietf.org/html/rfc7231#section-5.3.1 for details.
   */
  def qValueFor(mediaType: MediaType): Float =
    acceptedMediaRanges match {
      case Nil ⇒ 1.0f
      case x   ⇒ x collectFirst { case r if r matches mediaType ⇒ r.qValue } getOrElse 0f
    }

  /**
   * Determines whether the given [[akka.http.scaladsl.model.MediaType]] is accepted by the client.
   */
  def isAccepted(mediaType: MediaType): Boolean = qValueFor(mediaType) > 0f
}

final class CharsetNegotiator(requestHeaders: Seq[HttpHeader]) {

  /**
   * The charset-ranges accepted by the client according to given request headers, sorted by
   * 1. increasing generality (i.e. most specific first)
   * 2. decreasing q-value (only for ranges targeting a single HttpCharset)
   * 3. order of appearance in the `Accept-Charset` header(s)
   */
  val acceptedCharsetRanges: List[HttpCharsetRange] =
    (for {
      `Accept-Charset`(charsetRanges) ← requestHeaders
      range ← charsetRanges
    } yield range).sortBy { // `sortBy` is stable, i.e. upholds the original order on identical keys
      case _: HttpCharsetRange.`*` ⇒ 1f // most general, needs to come last
      case x                       ⇒ -x.qValue // all others come first
    }.toList

  /**
   * Returns the q-value that the client (implicitly or explicitly) attaches to the given charset.
   * See http://tools.ietf.org/html/rfc7231#section-5.3.1 for details.
   */
  def qValueFor(charset: HttpCharset): Float =
    acceptedCharsetRanges match {
      case Nil ⇒ 1.0f
      case x   ⇒ x collectFirst { case r if r matches charset ⇒ r.qValue } getOrElse 0f
    }

  /**
   * Determines whether the given charset is accepted by the client.
   */
  def isAccepted(charset: HttpCharset): Boolean = qValueFor(charset) > 0f

  /**
   * Picks the charset that is most preferred by the client with a bias towards UTF-8,
   * i.e. if the client accepts all charsets with equal preference then UTF-8 is picked.
   * If the client doesn't accept any charsets the method returns `None`.
   *
   * See also: http://tools.ietf.org/html/rfc7231#section-5.3.3
   */
  def pickBest: Option[HttpCharset] =
    acceptedCharsetRanges match {
      case Nil                                      ⇒ Some(`UTF-8`)
      case HttpCharsetRange.One(cs, _) :: _         ⇒ Some(cs)
      case HttpCharsetRange.`*`(qv) :: _ if qv > 0f ⇒ Some(`UTF-8`)
      case _                                        ⇒ None
    }
}

final class ContentNegotiator(requestHeaders: Seq[HttpHeader]) {
  import ContentNegotiator.Alternative

  val mtn = new MediaTypeNegotiator(requestHeaders)
  val csn = new CharsetNegotiator(requestHeaders)

  def qValueFor(alternative: Alternative): Float =
    alternative match {
      case Alternative.ContentType(ct: ContentType.NonBinary) ⇒
        math.min(mtn.qValueFor(ct.mediaType), csn.qValueFor(ct.charset))
      case x ⇒ mtn.qValueFor(x.mediaType)
    }

  /**
   * Picks the best of the given content alternatives given the preferences
   * the client indicated in the request's `Accept` and `Accept-Charset` headers.
   * See http://tools.ietf.org/html/rfc7231#section-5.3.2 ff for details on the negotiation logic.
   *
   * If there are several best alternatives that the client has equal preference for
   * the order of the given alternatives is used as a tie breaker (first one wins).
   *
   * If none of the given alternatives is acceptable to the client the methods return `None`.
   */
  def pickContentType(alternatives: List[Alternative]): Option[ContentType] =
    alternatives
      .map(alt ⇒ alt → qValueFor(alt))
      .sortBy(-_._2)
      .collectFirst { case (alt, q) if q > 0f ⇒ alt }
      .flatMap {
        case Alternative.ContentType(ct) ⇒ Some(ct)
        case Alternative.MediaType(mt)   ⇒ csn.pickBest.map(mt.withCharset)
      }
}

object ContentNegotiator {
  sealed trait Alternative {
    def mediaType: MediaType
    def format: String
  }
  object Alternative {
    implicit def apply(contentType: model.ContentType): ContentType = ContentType(contentType)
    implicit def apply(mediaType: model.MediaType): Alternative =
      mediaType match {
        case x: model.MediaType.Binary           ⇒ ContentType(x)
        case x: model.MediaType.WithFixedCharset ⇒ ContentType(x)
        case x: model.MediaType.WithOpenCharset  ⇒ MediaType(x)
      }

    case class ContentType(contentType: model.ContentType) extends Alternative {
      def mediaType = contentType.mediaType
      def format = contentType.toString
    }
    case class MediaType(mediaType: model.MediaType.WithOpenCharset) extends Alternative {
      def format = mediaType.toString
    }
  }

  def apply(requestHeaders: Seq[HttpHeader]) = new ContentNegotiator(requestHeaders)
}

final class EncodingNegotiator(requestHeaders: Seq[HttpHeader]) {

  /**
   * The encoding-ranges accepted by the client according to given request headers, sorted by
   * 1. increasing generality (i.e. most specific first)
   * 2. decreasing q-value (only for ranges targeting a single HttpEncoding)
   * 3. order of appearance in the `Accept-Encoding` header(s)
   */
  val acceptedEncodingRanges: List[HttpEncodingRange] =
    (for {
      `Accept-Encoding`(encodingRanges) ← requestHeaders
      range ← encodingRanges
    } yield range).sortBy { // `sortBy` is stable, i.e. upholds the original order on identical keys
      case _: HttpEncodingRange.`*` ⇒ 1f // most general, needs to come last
      case x                        ⇒ -x.qValue // all others come first
    }.toList

  /**
   * Returns the q-value that the client (implicitly or explicitly) attaches to the given encoding.
   * See http://tools.ietf.org/html/rfc7231#section-5.3.1 for details.
   */
  def qValueFor(encoding: HttpEncoding): Float =
    acceptedEncodingRanges match {
      case Nil ⇒ 1.0f
      case x   ⇒ x collectFirst { case r if r matches encoding ⇒ r.qValue } getOrElse 0f
    }

  /**
   * Determines whether the given encoding is accepted by the client.
   */
  def isAccepted(encoding: HttpEncoding): Boolean = qValueFor(encoding) > 0f

  /**
   * Determines whether the request has an `Accept-Encoding` clause matching the given encoding.
   */
  def hasMatchingFor(encoding: HttpEncoding): Boolean =
    acceptedEncodingRanges.exists(_ matches encoding)

  /**
   * Picks the best of the given encoding alternatives given the preferences
   * the client indicated in the request's `Accept-Encoding` headers.
   * See http://tools.ietf.org/html/rfc7231#section-5.3.4 for details on the negotiation logic.
   *
   * If there are several best encoding alternatives that the client has equal preference for
   * the order of the given alternatives is used as a tie breaker (first one wins).
   *
   * If none of the given alternatives is acceptable to the client the methods return `None`.
   */
  def pickEncoding(alternatives: List[HttpEncoding]): Option[HttpEncoding] =
    alternatives
      .map(alt ⇒ alt → qValueFor(alt))
      .sortBy(-_._2)
      .collectFirst { case (alt, q) if q > 0f ⇒ alt }
}

object EncodingNegotiator {
  def apply(requestHeaders: Seq[HttpHeader]) = new EncodingNegotiator(requestHeaders)
}

final class LanguageNegotiator(requestHeaders: Seq[HttpHeader]) {

  /**
   * The language-ranges accepted by the client according to given request headers, sorted by
   * 1. increasing generality (i.e. most specific first)
   * 2. decreasing q-value (only for ranges targeting a single Language)
   * 3. order of appearance in the `Accept-Language` header(s)
   */
  val acceptedLanguageRanges: List[LanguageRange] =
    (for {
      `Accept-Language`(languageRanges) ← requestHeaders
      range ← languageRanges
    } yield range).sortBy { // `sortBy` is stable, i.e. upholds the original order on identical keys
      case _: LanguageRange.`*` ⇒ 1f // most general, needs to come last
      case x                    ⇒ -(2 * x.subTags.size + x.qValue) // more subtags -> more specific -> go first
    }.toList

  /**
   * Returns the q-value that the client (implicitly or explicitly) attaches to the given language.
   * See http://tools.ietf.org/html/rfc7231#section-5.3.1 for details.
   */
  def qValueFor(language: Language): Float =
    acceptedLanguageRanges match {
      case Nil ⇒ 1.0f
      case x   ⇒ x collectFirst { case r if r matches language ⇒ r.qValue } getOrElse 0f
    }

  /**
   * Determines whether the given language is accepted by the client.
   */
  def isAccepted(language: Language): Boolean = qValueFor(language) > 0f

  /**
   * Picks the best of the given language alternatives given the preferences
   * the client indicated in the request's `Accept-Language` headers.
   * See http://tools.ietf.org/html/rfc7231#section-5.3.5 for details on the negotiation logic.
   *
   * If there are several best language alternatives that the client has equal preference for
   * the order of the given alternatives is used as a tie breaker (first one wins).
   *
   * If none of the given alternatives is acceptable to the client the methods return `None`.
   */
  def pickLanguage(alternatives: List[Language]): Option[Language] =
    alternatives
      .map(alt ⇒ alt → qValueFor(alt))
      .sortBy(-_._2)
      .collectFirst { case (alt, q) if q > 0f ⇒ alt }
}

object LanguageNegotiator {
  def apply(requestHeaders: Seq[HttpHeader]) = new LanguageNegotiator(requestHeaders)
}
