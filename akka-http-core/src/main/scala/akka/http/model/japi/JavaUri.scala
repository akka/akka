/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi

import java.{ util ⇒ ju, lang ⇒ jl }

import akka.http.model
import akka.japi.Option
import scala.annotation.tailrec

import JavaMapping.Implicits._

/** INTERNAL API */
protected[model] case class JavaUri(uri: model.Uri) extends Uri {
  def isRelative: Boolean = uri.isRelative
  def isAbsolute: Boolean = uri.isAbsolute
  def isEmpty: Boolean = uri.isEmpty

  def scheme(): String = uri.scheme
  def host(): Host = uri.authority.host
  def port(): Int = uri.authority.port
  def userInfo(): String = uri.authority.userinfo

  def path(): String = uri.path.toString

  import collection.JavaConverters._
  def pathSegments(): jl.Iterable[String] = {
    import model.Uri.Path
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
  def parameters(): jl.Iterable[Uri.Parameter] = uri.query.map(t ⇒ Param(t._1, t._2): Uri.Parameter).toIterable.asJava
  def containsParameter(key: String): Boolean = uri.query.get(key).isDefined
  def parameter(key: String): Option[String] = uri.query.get(key)

  case class Param(key: String, value: String) extends Uri.Parameter

  def fragment: Option[String] = uri.fragment

  // Modification methods

  def t(f: model.Uri ⇒ model.Uri): Uri = JavaUri(f(uri))

  def scheme(scheme: String): Uri = t(_.withScheme(scheme))

  def host(host: Host): Uri = t(_.withHost(host.asScala))
  def host(host: String): Uri = t(_.withHost(host))
  def port(port: Int): Uri = t(_.withPort(port))
  def userInfo(userInfo: String): Uri = t(_.withUserInfo(userInfo))

  def path(path: String): Uri = t(_.withPath(model.Uri.Path(path)))

  def toRelative: Uri = t(_.toRelative)

  def query(query: String): Uri = t(_.withQuery(query))
  def addParameter(key: String, value: String): Uri = t { u ⇒
    u.withQuery(((key -> value) +: u.query.reverse).reverse)
  }

  def addPathSegment(segment: String): Uri = t { u ⇒
    import model.Uri.Path
    import Path._

    @tailrec def endsWithSlash(path: Path): Boolean = path match {
      case Empty               ⇒ false
      case Slash(Empty)        ⇒ true
      case Slash(tail)         ⇒ endsWithSlash(tail)
      case Segment(head, tail) ⇒ endsWithSlash(tail)
    }

    val newPath =
      if (endsWithSlash(u.path)) u.path ++ Path(segment)
      else u.path ++ Path./(segment)

    u.withPath(newPath)
  }

  def fragment(fragment: Option[String]): Uri = t(_.copy(fragment = fragment))
  def fragment(fragment: String): Uri = t(_.withFragment(fragment))
}