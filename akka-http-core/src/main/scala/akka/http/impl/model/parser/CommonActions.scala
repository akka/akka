/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.http.impl.util._
import akka.http.scaladsl.model.MediaType.Binary
import akka.http.scaladsl.model._
import akka.stream.impl.ConstantFun

/** INTERNAL API */
private[parser] trait CommonActions {

  def customMediaTypes: MediaTypes.FindCustom

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
        // attempt fetching custom media type if configured
        if (areCustomMediaTypesDefined)
          customMediaTypes(mainLower, subType) getOrElse fallbackMediaType(subType, params, mainLower)
        else MediaTypes.getForKey((mainLower, subLower)) match {
          case Some(registered) ⇒ if (params.isEmpty) registered else registered.withParams(params)
          case None ⇒
            if (charsetDefined)
              MediaType.customWithOpenCharset(mainLower, subType, params = params, allowArbitrarySubtypes = true)
            else
              fallbackMediaType(subType, params, mainLower)
        }
    }
  }

  /** Provide a generic MediaType when no known-ones matched. */
  private def fallbackMediaType(subType: String, params: Map[String, String], mainLower: String): Binary =
    MediaType.customBinary(mainLower, subType, MediaType.Compressible, params = params, allowArbitrarySubtypes = true)

  def getCharset(name: String): HttpCharset =
    HttpCharsets
      .getForKeyCaseInsensitive(name)
      .getOrElse(HttpCharset.custom(name))

  @inline private def areCustomMediaTypesDefined: Boolean = customMediaTypes ne ConstantFun.two2none

}