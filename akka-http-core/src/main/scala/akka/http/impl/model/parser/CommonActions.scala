/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.annotation.InternalApi
import akka.http.impl.util._
import akka.http.scaladsl.model._

/** INTERNAL API */
@InternalApi
private[parser] trait CommonActions {

  def customMediaTypes: MediaTypes.FindCustom
  def areNoCustomMediaTypesDefined: Boolean

  type StringMapBuilder = scala.collection.mutable.Builder[(String, String), Map[String, String]]

  def getMediaType(mainType: String, subType: String, charsetDefined: Boolean,
                   params: Map[String, String]): MediaType = {
    import MediaTypes._
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
        if (areNoCustomMediaTypesDefined) // try to prevent closure creation for usual case without custom media types
          getPredefinedOrCreateCustom(mainType, subType, charsetDefined, params)
        else
          customMediaTypes(mainLower, subLower) getOrElse
            getPredefinedOrCreateCustom(mainLower, subLower, charsetDefined, params)
    }
  }

  private def getPredefinedOrCreateCustom(mainLower: String, subLower: String, charsetDefined: Boolean,
                                          params: Map[String, String]): MediaType =
    MediaTypes.getForKey((mainLower, subLower)) match {
      case Some(registered) ⇒ if (params.isEmpty) registered else registered.withParams(params)
      case None ⇒
        if (charsetDefined)
          MediaType.customWithOpenCharset(mainLower, subLower, params = params, allowArbitrarySubtypes = true)
        else
          MediaType.customBinary(mainLower, subLower, MediaType.Compressible, params = params, allowArbitrarySubtypes = true)
    }

  def getCharset(name: String): HttpCharset =
    HttpCharsets
      .getForKeyCaseInsensitive(name)
      .getOrElse(HttpCharset.custom(name))
}
