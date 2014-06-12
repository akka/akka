/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import MediaTypes._

private[parser] trait CommonActions {

  type StringMapBuilder = scala.collection.mutable.Builder[(String, String), Map[String, String]]

  def getMediaType(mainType: String, subType: String, params: Map[String, String]): MediaType = {
    mainType.toLowerCase match {
      case "multipart" ⇒ subType.toLowerCase match {
        case "mixed"       ⇒ multipart.mixed(params)
        case "alternative" ⇒ multipart.alternative(params)
        case "related"     ⇒ multipart.related(params)
        case "form-data"   ⇒ multipart.`form-data`(params)
        case "signed"      ⇒ multipart.signed(params)
        case "encrypted"   ⇒ multipart.encrypted(params)
        case custom        ⇒ multipart(custom, params)
      }
      case mainLower ⇒
        MediaTypes.getForKey((mainLower, subType.toLowerCase)) match {
          case Some(registered) ⇒ if (params.isEmpty) registered else registered.withParams(params)
          case None             ⇒ MediaType.custom(mainType, subType, params = params, allowArbitrarySubtypes = true)
        }
    }
  }

  def getCharset(name: String): HttpCharset =
    HttpCharsets
      .getForKey(name.toLowerCase)
      .orElse(HttpCharset.custom(name))
      .getOrElse(throw new ParsingException("Unsupported charset", name))
}