/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.lang.{ Iterable ⇒ JIterable }
import java.util.function.{ Function ⇒ JFunction }
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.regex.Pattern

import scala.collection.JavaConverters._

import akka.http.javadsl.server.RegexConverters.toScala
import akka.http.scaladsl.server.{ Directives ⇒ D }

abstract class HostDirectives extends HeaderDirectives {
  /**
   * Extracts the hostname part of the Host request header value.
   */
  def extractHost(inner: JFunction[String, Route]): Route = ScalaRoute {
    D.extractHost { host ⇒ inner.apply(host).toScala }
  }

  /**
   * Rejects all requests with a host name different from the given ones.
   */
  def host(hostNames: JIterable[String], inner: Supplier[Route]): Route = ScalaRoute {
    D.host(hostNames.asScala.toSeq: _*) { inner.get().toScala }
  }

  /**
   * Rejects all requests with a host name different from the given one.
   */
  def host(hostName: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.host(hostName) { inner.get().toScala }
  }

  /**
   * Rejects all requests for whose host name the given predicate function returns false.
   */
  def host(predicate: Predicate[String], inner: Supplier[Route]): Route = ScalaRoute {
    D.host(s ⇒ predicate.test(s)) { inner.get().toScala }
  }

  /**
   * Rejects all requests with a host name that doesn't have a prefix matching the given regular expression.
   * For all matching requests the prefix string matching the regex is extracted and passed to the inner route.
   * If the regex contains a capturing group only the string matched by this group is extracted.
   * If the regex contains more than one capturing group an IllegalArgumentException is thrown.
   */
  def host(regex: Pattern, inner: JFunction[String, Route]): Route = ScalaRoute {
    D.host(toScala(regex)) { s ⇒ inner.apply(s).toScala }
  }

}
