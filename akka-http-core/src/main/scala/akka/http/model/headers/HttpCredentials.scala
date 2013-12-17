package akka.http.model
package headers

import akka.http.model.HttpCharsets._
import akka.http.rendering._
import akka.http.util.Base64

sealed trait HttpCredentials extends ValueRenderable

case class BasicHttpCredentials(username: String, password: String) extends HttpCredentials {
  def render[R <: Rendering](r: R): r.type = {
    val userPass = username + ':' + password
    val bytes = userPass.getBytes(`ISO-8859-1`.nioCharset)
    val cookie = Base64.rfc2045.encodeToChar(bytes, false)
    r ~~ "Basic " ~~ cookie
  }
}

object BasicHttpCredentials {
  def apply(credentials: String): BasicHttpCredentials = {
    val bytes = Base64.rfc2045.decodeFast(credentials)
    val userPass = new String(bytes, `ISO-8859-1`.nioCharset)
    userPass.indexOf(':') match {
      case -1 ⇒ apply(userPass, "")
      case ix ⇒ apply(userPass.substring(0, ix), userPass.substring(ix + 1))
    }
  }
}

case class OAuth2BearerToken(token: String) extends HttpCredentials {
  def render[R <: Rendering](r: R): r.type = r ~~ "Bearer " ~~ token
}

case class GenericHttpCredentials(scheme: String, token: String,
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
