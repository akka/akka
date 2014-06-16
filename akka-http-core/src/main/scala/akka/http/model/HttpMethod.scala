/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import akka.http.util.{ SingletonValueRenderable, ObjectRegistry }

/**
 * The method of an HTTP request.
 * @param fingerprint unique Int value for faster equality checks (uniqueness is verified during registration)
 * @param isSafe true if the resource should not be altered on the server
 * @param isIdempotent true if requests can be safely (& automatically) repeated
 * @param isEntityAccepted true if meaning of request entities is properly defined
 */
final case class HttpMethod private[http] (override val value: String,
                                           fingerprint: Int,
                                           isSafe: Boolean,
                                           isIdempotent: Boolean,
                                           isEntityAccepted: Boolean) extends japi.HttpMethod with SingletonValueRenderable {
  def name = value

  override def hashCode(): Int = fingerprint
  override def equals(obj: Any): Boolean =
    obj match {
      case m: HttpMethod ⇒ fingerprint == m.fingerprint
      case _             ⇒ false
    }

  override def toString: String = s"HttpMethod($value)"
}

object HttpMethod {
  def custom(value: String, safe: Boolean, idempotent: Boolean, entityAccepted: Boolean): HttpMethod =
    custom(value, value.##, safe, idempotent, entityAccepted)

  def custom(value: String, fingerprint: Int, safe: Boolean, idempotent: Boolean, entityAccepted: Boolean): HttpMethod = {
    require(value.nonEmpty, "value must be non-empty")
    require(!safe || idempotent, "An HTTP method cannot be safe without being idempotent")
    apply(value, fingerprint, safe, idempotent, entityAccepted)
  }
}

object HttpMethods extends ObjectRegistry[String, HttpMethod] {
  def register(method: HttpMethod): HttpMethod = {
    require(registry.values.forall(_.fingerprint != method.fingerprint), "Method fingerprint collision")
    register(method.value, method)
  }

  // format: OFF
  val CONNECT = register(HttpMethod("CONNECT", 0x01, isSafe = false, isIdempotent = false, isEntityAccepted = false))
  val DELETE  = register(HttpMethod("DELETE" , 0x02, isSafe = false, isIdempotent = true , isEntityAccepted = false))
  val GET     = register(HttpMethod("GET"    , 0x03, isSafe = true , isIdempotent = true , isEntityAccepted = false))
  val HEAD    = register(HttpMethod("HEAD"   , 0x04, isSafe = true , isIdempotent = true , isEntityAccepted = false))
  val OPTIONS = register(HttpMethod("OPTIONS", 0x05, isSafe = true , isIdempotent = true , isEntityAccepted = true))
  val PATCH   = register(HttpMethod("PATCH"  , 0x06, isSafe = false, isIdempotent = false, isEntityAccepted = true))
  val POST    = register(HttpMethod("POST"   , 0x07, isSafe = false, isIdempotent = false, isEntityAccepted = true))
  val PUT     = register(HttpMethod("PUT"    , 0x08, isSafe = false, isIdempotent = true , isEntityAccepted = true))
  val TRACE   = register(HttpMethod("TRACE"  , 0x09, isSafe = true , isIdempotent = true , isEntityAccepted = false))
  // format: ON
}
