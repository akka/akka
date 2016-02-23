/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import scala.annotation.tailrec
import akka.parboiled2.Parser
import akka.http.scaladsl.model.{ ParsingException, IllegalUriException }
import akka.http.scaladsl.model.headers._

private[parser] trait LinkHeader { this: Parser with CommonRules with CommonActions ⇒
  import CharacterClasses._

  // http://tools.ietf.org/html/rfc5988#section-5
  def `link` = rule {
    zeroOrMore(`link-value`).separatedBy(listSep) ~ EOI ~> (Link(_))
  }

  def `link-value` = rule {
    ws('<') ~ UriReference('>') ~ ws('>') ~ oneOrMore(ws(';') ~ `link-param`) ~> (sanitize(_)) ~> (LinkValue(_, _: _*))
  }

  def `link-param` = rule(
    ws("rel") ~ ws('=') ~ `relation-types` ~> LinkParams.rel
      | ws("anchor") ~ ws('=') ~ ws('"') ~ UriReference('"') ~ ws('"') ~> LinkParams.anchor
      | ws("rev") ~ ws('=') ~ `relation-types` ~> LinkParams.rev
      | ws("hreflang") ~ ws('=') ~ language ~> LinkParams.hreflang
      | ws("media") ~ ws('=') ~ word ~> LinkParams.media
      | ws("title") ~ ws('=') ~ word ~> LinkParams.title
      | ws("title*") ~ ws('=') ~ word ~> LinkParams.`title*` // support full `ext-value` notation from http://tools.ietf.org/html/rfc5987#section-3.2.1
      | ws("type") ~ ws('=') ~ (ws('"') ~ `link-media-type` ~ ws('"') | `link-media-type`) ~> LinkParams.`type`)
  // TODO: support `link-extension`

  def `relation-types` = rule(
    ws('"') ~ oneOrMore(`relation-type`).separatedBy(oneOrMore(SP)) ~> (_.mkString(" ")) ~ ws('"')
      | `relation-type` ~ OWS)

  def `relation-type` = rule { `reg-rel-type` | `ext-rel-type` }

  def `reg-rel-type` = rule {
    capture(LOWER_ALPHA ~ zeroOrMore(`reg-rel-type-octet`)) ~ !VCHAR
  }

  def `ext-rel-type` = rule {
    URI
  }

  ////////////////////////////// helpers ///////////////////////////////////

  def UriReference(terminationChar: Char) = rule {
    capture(oneOrMore(!terminationChar ~ VCHAR)) ~> (newUriParser(_).parseUriReference())
  }

  def URI = rule {
    capture(oneOrMore(!'"' ~ !';' ~ !',' ~ VCHAR)) ~> { s ⇒
      try new UriParser(s).parseUriReference()
      catch {
        case IllegalUriException(info) ⇒ throw ParsingException(info.withSummaryPrepended("Illegal `Link` header relation-type"))
      }
      s
    }
  }

  def `link-media-type` = rule { `media-type` ~> ((mt, st, pm) ⇒ getMediaType(mt, st, pm contains "charset", pm.toMap)) }

  // filter out subsequent `rel`, `media`, `title`, `type` and `type*` params
  @tailrec private def sanitize(params: Seq[LinkParam], result: Seq[LinkParam] = Nil, seenRel: Boolean = false,
                                seenMedia: Boolean = false, seenTitle: Boolean = false, seenTitleS: Boolean = false,
                                seenType: Boolean = false): Seq[LinkParam] =
    params match {
      case Seq((x: LinkParams.rel), tail @ _*)      ⇒ sanitize(tail, if (seenRel) result else result :+ x, seenRel = true, seenMedia, seenTitle, seenTitleS, seenType)
      case Seq((x: LinkParams.media), tail @ _*)    ⇒ sanitize(tail, if (seenMedia) result else result :+ x, seenRel, seenMedia = true, seenTitle, seenTitleS, seenType)
      case Seq((x: LinkParams.title), tail @ _*)    ⇒ sanitize(tail, if (seenTitle) result else result :+ x, seenRel, seenMedia, seenTitle = true, seenTitleS, seenType)
      case Seq((x: LinkParams.`title*`), tail @ _*) ⇒ sanitize(tail, if (seenTitleS) result else result :+ x, seenRel, seenMedia, seenTitle, seenTitleS = true, seenType)
      case Seq((x: LinkParams.`type`), tail @ _*)   ⇒ sanitize(tail, if (seenType) result else result :+ x, seenRel, seenMedia, seenTitle, seenTitleS, seenType = true)
      case Seq(head, tail @ _*)                     ⇒ sanitize(tail, result :+ head, seenRel, seenMedia, seenTitle, seenTitleS, seenType)
      case Nil                                      ⇒ result
    }
}