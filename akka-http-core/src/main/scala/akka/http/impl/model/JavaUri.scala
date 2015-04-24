/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.model

import java.{ util ⇒ ju, lang ⇒ jl }
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

  import collection.JavaConverters._
  def pathSegments(): jl.Iterable[String] = {
    import sm.Uri.Path
    import Path._
    def gatherSegments(path: Path): List[String] = path match {
      case Empty               ⇒ Nil
      case Segment(head, tail) ⇒ head :: gatherSegments(tail)
      case Slash(tail)         ⇒ gatherSegments(tail)
    }
    gatherSegments(uri.path).asJava
  }

  def queryString(): String = uri.query.toString

  def parameterMap(): ju.Map[String, String] = uri.query.toMap.asJava
  def parameters(): jl.Iterable[Param] =
    uri.query.map { case (k, v) ⇒ new ju.AbstractMap.SimpleImmutableEntry(k, v): Param }.toIterable.asJava
  def containsParameter(key: String): Boolean = uri.query.get(key).isDefined
  def parameter(key: String): Option[String] = uri.query.get(key)

  type Param = ju.Map.Entry[String, String]

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

  def query(query: String): jm.Uri = t(_.withQuery(query))
  def addParameter(key: String, value: String): jm.Uri = t { u ⇒
    u.withQuery(((key -> value) +: u.query.reverse).reverse)
  }

  def addPathSegment(segment: String): jm.Uri = t { u ⇒
    import sm.Uri.Path
    val newPath =
      if (u.path.endsWithSlash) u.path ++ Path(segment)
      else u.path ++ Path./(segment)

    u.withPath(newPath)
  }

  def fragment(fragment: Option[String]): jm.Uri = t(_.copy(fragment = fragment))
  def fragment(fragment: String): jm.Uri = t(_.withFragment(fragment))
}