/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model

import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }

/**
 * The method of an HTTP request.
 * @param isSafe true if the resource should not be altered on the server
 * @param isIdempotent true if requests can be safely (& automatically) repeated
 * @param isEntityAccepted true if meaning of request entities is properly defined
 */
final case class HttpMethod private[http] (override val value: String,
                                           isSafe: Boolean,
                                           isIdempotent: Boolean,
                                           isEntityAccepted: Boolean) extends jm.HttpMethod with SingletonValueRenderable {
  def name = value
  override def toString: String = s"HttpMethod($value)"
}

object HttpMethod {
  def custom(name: String, safe: Boolean, idempotent: Boolean, entityAccepted: Boolean): HttpMethod = {
    require(name.nonEmpty, "value must be non-empty")
    require(!safe || idempotent, "An HTTP method cannot be safe without being idempotent")
    apply(name, safe, idempotent, entityAccepted)
  }

  /**
   * Creates a custom method by name and assumes properties conservatively to be
   * safe = idempotent = false and entityAccepted = true.
   */
  def custom(name: String): HttpMethod = custom(name, safe = false, idempotent = false, entityAccepted = true)
}

object HttpMethods extends ObjectRegistry[String, HttpMethod] {
  private def register(method: HttpMethod): HttpMethod = register(method.value, method)

  // format: OFF
  val CONNECT = register(HttpMethod("CONNECT", isSafe = false, isIdempotent = false, isEntityAccepted = false))
  val DELETE  = register(HttpMethod("DELETE" , isSafe = false, isIdempotent = true , isEntityAccepted = false))
  val GET     = register(HttpMethod("GET"    , isSafe = true , isIdempotent = true , isEntityAccepted = false))
  val HEAD    = register(HttpMethod("HEAD"   , isSafe = true , isIdempotent = true , isEntityAccepted = false))
  val OPTIONS = register(HttpMethod("OPTIONS", isSafe = true , isIdempotent = true , isEntityAccepted = true))
  val PATCH   = register(HttpMethod("PATCH"  , isSafe = false, isIdempotent = false, isEntityAccepted = true))
  val POST    = register(HttpMethod("POST"   , isSafe = false, isIdempotent = false, isEntityAccepted = true))
  val PUT     = register(HttpMethod("PUT"    , isSafe = false, isIdempotent = true , isEntityAccepted = true))
  val TRACE   = register(HttpMethod("TRACE"  , isSafe = true , isIdempotent = true , isEntityAccepted = false))
  // format: ON
}
