/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.parboiled2.util.Base64
import akka.http.scaladsl.model.HttpCharsets._
import akka.http.impl.util.{ Rendering, ValueRenderable }
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._

sealed abstract class HttpCredentials extends jm.headers.HttpCredentials with ValueRenderable {
  def scheme: String
  def token: String
  def params: Map[String, String]

  /** Java API */
  def getParams: java.util.Map[String, String] = params.asJava
}

final case class BasicHttpCredentials(username: String, password: String) extends jm.headers.BasicHttpCredentials {
  val cookie = {
    val userPass = username + ':' + password
    val bytes = userPass.getBytes(`UTF-8`.nioCharset)
    Base64.rfc2045.encodeToChar(bytes, false)
  }
  def render[R <: Rendering](r: R): r.type = r ~~ "Basic " ~~ cookie

  override def scheme: String = "Basic"
  override def token: String = String.valueOf(cookie)
  override def params: Map[String, String] = Map.empty
}

object BasicHttpCredentials {
  def apply(credentials: String): BasicHttpCredentials = {
    val bytes = Base64.rfc2045.decodeFast(credentials)
    val userPass = new String(bytes, `UTF-8`.nioCharset)
    userPass.indexOf(':') match {
      case -1 ⇒ apply(userPass, "")
      case ix ⇒ apply(userPass.substring(0, ix), userPass.substring(ix + 1))
    }
  }
}

final case class OAuth2BearerToken(token: String) extends jm.headers.OAuth2BearerToken {
  def render[R <: Rendering](r: R): r.type = r ~~ "Bearer " ~~ token

  override def scheme: String = "Bearer"
  override def params: Map[String, String] = Map.empty
}

final case class GenericHttpCredentials(scheme: String, token: String,
                                        params: Map[String, String] = Map.empty) extends HttpCredentials {
  def render[R <: Rendering](r: R): r.type = {
    r ~~ scheme
    if (!token.isEmpty) r ~~ ' ' ~~ token
    if (params.nonEmpty)
      params foreach new (((String, String)) ⇒ Unit) {
        var first = true
        def apply(kvp: (String, String)): Unit = {
          val (k, v) = kvp
          if (first) { r ~~ ' '; first = false } else r ~~ ','
          if (!k.isEmpty) r ~~ k ~~ '='
          r ~~# v
        }
      }
    r
  }
}

object GenericHttpCredentials {
  def apply(scheme: String, params: Map[String, String]): GenericHttpCredentials = apply(scheme, "", params)
}
