/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.util.matching.Regex
import akka.http.impl.util._

trait HostDirectives {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Extracts the hostname part of the Host request header value.
   */
  def extractHost: Directive1[String] = HostDirectives._extractHost

  /**
   * Rejects all requests with a host name different from the given ones.
   */
  def host(hostNames: String*): Directive0 = host(hostNames.contains(_))

  //#require-host
  /**
   * Rejects all requests for whose host name the given predicate function returns false.
   */
  def host(predicate: String ⇒ Boolean): Directive0 = extractHost.require(predicate)
  //#

  /**
   * Rejects all requests with a host name that doesn't have a prefix matching the given regular expression.
   * For all matching requests the prefix string matching the regex is extracted and passed to the inner route.
   * If the regex contains a capturing group only the string matched by this group is extracted.
   * If the regex contains more than one capturing group an IllegalArgumentException is thrown.
   */
  def host(regex: Regex): Directive1[String] = {
    def forFunc(regexMatch: String ⇒ Option[String]): Directive1[String] = {
      extractHost.flatMap { name ⇒
        regexMatch(name) match {
          case Some(matched) ⇒ provide(matched)
          case None          ⇒ reject
        }
      }
    }

    regex.groupCount match {
      case 0 ⇒ forFunc(regex.findPrefixOf(_))
      case 1 ⇒ forFunc(regex.findPrefixMatchOf(_).map(_.group(1)))
      case _ ⇒ throw new IllegalArgumentException("Path regex '" + regex.pattern.pattern +
        "' must not contain more than one capturing group")
    }
  }

}

object HostDirectives extends HostDirectives {
  import BasicDirectives._

  private val _extractHost: Directive1[String] =
    extract(_.request.uri.authority.host.address)
}
