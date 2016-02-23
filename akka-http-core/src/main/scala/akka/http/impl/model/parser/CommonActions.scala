/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.http.impl.util._
import akka.http.scaladsl.model._
import MediaTypes._

private[parser] trait CommonActions {

  type StringMapBuilder = scala.collection.mutable.Builder[(String, String), Map[String, String]]

  def getMediaType(mainType: String, subType: String, charsetDefined: Boolean,
                   params: Map[String, String]): MediaType = {
    val subLower = subType.toRootLowerCase
    mainType.toRootLowerCase match {
      case "multipart" ⇒ subLower match {
        case "mixed"       ⇒ multipart.mixed(params)
        case "alternative" ⇒ multipart.alternative(params)
        case "related"     ⇒ multipart.related(params)
        case "form-data"   ⇒ multipart.`form-data`(params)
        case "signed"      ⇒ multipart.signed(params)
        case "encrypted"   ⇒ multipart.encrypted(params)
        case custom        ⇒ MediaType.customMultipart(custom, params)
      }
      case mainLower ⇒
        MediaTypes.getForKey((mainLower, subLower)) match {
          case Some(registered) ⇒ if (params.isEmpty) registered else registered.withParams(params)
          case None ⇒
            if (charsetDefined)
              MediaType.customWithOpenCharset(mainLower, subType, params = params, allowArbitrarySubtypes = true)
            else
              MediaType.customBinary(mainLower, subType, MediaType.Compressible, params = params,
                allowArbitrarySubtypes = true)
        }
    }
  }

  def getCharset(name: String): HttpCharset =
    HttpCharsets
      .getForKeyCaseInsensitive(name)
      .getOrElse(HttpCharset.custom(name))
}