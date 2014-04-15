/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import MediaTypes._

private[parser] trait CommonActions {

  type StringMapBuilder = scala.collection.mutable.Builder[(String, String), Map[String, String]]

  def getMediaType(mainType: String, subType: String, parameters: Map[String, String]): MediaType = {
    mainType.toLowerCase match {
      case "multipart" ⇒ subType.toLowerCase match {
        case "mixed"       ⇒ multipart.mixed(parameters)
        case "alternative" ⇒ multipart.alternative(parameters)
        case "related"     ⇒ multipart.related(parameters)
        case "form-data"   ⇒ multipart.`form-data`(parameters)
        case "signed"      ⇒ multipart.signed(parameters)
        case "encrypted"   ⇒ multipart.encrypted(parameters)
        case custom        ⇒ multipart(custom, parameters)
      }
      case mainLower ⇒
        MediaTypes.getForKey((mainLower, subType.toLowerCase)) match {
          case Some(registered) ⇒ if (parameters.isEmpty) registered else registered.withParameters(parameters)
          case None             ⇒ MediaType.custom(mainType, subType, parameters = parameters, allowArbitrarySubtypes = true)
        }
    }
  }

  def getCharset(name: String): HttpCharset =
    HttpCharsets
      .getForKey(name.toLowerCase)
      .orElse(HttpCharset.custom(name))
      .getOrElse(throw new ParsingException("Unsupported charset", name))
}