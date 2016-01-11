/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.model

import java.nio.charset.Charset
import java.{ lang ⇒ jl }
import akka.http.scaladsl.model.Uri.ParsingMode
import akka.japi.Option
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.scaladsl.{ model ⇒ sm }
import akka.http.impl.util.JavaMapping.Implicits._

/** INTERNAL API */
case class JavaUri(uri: sm.Uri) extends jm.Uri {
  def isRelative: Boolean = uri.isRelative
  def isAbsolute: Boolean = uri.isAbsolute
  def isEmpty: Boolean = uri.isEmpty

  def scheme(): String = uri.scheme
  def host(): jm.Host = uri.authority.host
  def port(): Int = uri.authority.port
  def userInfo(): String = uri.authority.userinfo

  def path(): String = uri.path.toString

  def pathSegments(): jl.Iterable[String] = {
    import sm.Uri.Path._
    def gatherSegments(path: sm.Uri.Path): List[String] = path match {
      case Empty               ⇒ Nil
      case Segment(head, tail) ⇒ head :: gatherSegments(tail)
      case Slash(tail)         ⇒ gatherSegments(tail)
    }
    import collection.JavaConverters._
    gatherSegments(uri.path).asJava
  }

  def rawQueryString: Option[String] = uri.rawQueryString
  def queryString(charset: Charset): Option[String] = uri.queryString(charset)
  def query: jm.Query = uri.query().asJava
  def query(charset: Charset, mode: ParsingMode): jm.Query = uri.query(charset, mode).asJava

  def fragment: Option[String] = uri.fragment

  // Modification methods

  def t(f: sm.Uri ⇒ sm.Uri): jm.Uri = JavaUri(f(uri))

  def scheme(scheme: String): jm.Uri = t(_.withScheme(scheme))

  def host(host: jm.Host): jm.Uri = t(_.withHost(host.asScala))
  def host(host: String): jm.Uri = t(_.withHost(host))
  def port(port: Int): jm.Uri = t(_.withPort(port))
  def userInfo(userInfo: String): jm.Uri = t(_.withUserInfo(userInfo))

  def path(path: String): jm.Uri = t(_.withPath(sm.Uri.Path(path)))

  def toRelative: jm.Uri = t(_.toRelative)

  def rawQueryString(rawQuery: String): jm.Uri = t(_.withRawQueryString(rawQuery))
  def query(query: jm.Query): jm.Uri = t(_.withQuery(query.asScala))

  def addPathSegment(segment: String): jm.Uri = t { u ⇒
    val newPath =
      if (u.path.endsWithSlash) u.path ++ sm.Uri.Path(segment)
      else u.path ++ sm.Uri.Path./(segment)

    u.withPath(newPath)
  }

  def fragment(fragment: Option[String]): jm.Uri = t(_.copy(fragment = fragment))
  def fragment(fragment: String): jm.Uri = t(_.withFragment(fragment))

  override def toString: String = uri.toString
}