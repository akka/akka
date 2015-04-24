/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.model.parser

import akka.http.impl.util._
import akka.http.scaladsl.model._
import MediaTypes._

private[parser] trait CommonActions {

  type StringMapBuilder = scala.collection.mutable.Builder[(String, String), Map[String, String]]

  def getMediaType(mainType: String, subType: String, params: Map[String, String]): MediaType = {
    mainType.toRootLowerCase match {
      case "multipart" ⇒ subType.toRootLowerCase match {
        case "mixed"       ⇒ multipart.mixed(params)
        case "alternative" ⇒ multipart.alternative(params)
        case "related"     ⇒ multipart.related(params)
        case "form-data"   ⇒ multipart.`form-data`(params)
        case "signed"      ⇒ multipart.signed(params)
        case "encrypted"   ⇒ multipart.encrypted(params)
        case custom        ⇒ multipart(custom, params)
      }
      case mainLower ⇒
        MediaTypes.getForKey((mainLower, subType.toRootLowerCase)) match {
          case Some(registered) ⇒ if (params.isEmpty) registered else registered.withParams(params)
          case None ⇒ MediaType.custom(mainType, subType, encoding = MediaType.Encoding.Open,
            params = params, allowArbitrarySubtypes = true)
        }
    }
  }

  def getCharset(name: String): HttpCharset =
    HttpCharsets
      .getForKeyCaseInsensitive(name)
      .getOrElse(HttpCharset.custom(name))
}