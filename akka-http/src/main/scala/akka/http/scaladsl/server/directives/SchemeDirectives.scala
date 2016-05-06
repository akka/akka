/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

/**
 * @groupname scheme Scheme directives
 * @groupprio scheme 210
 */
trait SchemeDirectives {
  import BasicDirectives._

  /**
   * Extracts the Uri scheme from the request.
   *
   * @group scheme
   */
  def extractScheme: Directive1[String] = SchemeDirectives._extractScheme

  /**
   * Rejects all requests whose Uri scheme does not match the given one.
   *
   * @group scheme
   */
  def scheme(name: String): Directive0 =
    extractScheme.require(_ == name, SchemeRejection(name)) & cancelRejections(classOf[SchemeRejection])
}

object SchemeDirectives extends SchemeDirectives {
  import BasicDirectives._

  private val _extractScheme: Directive1[String] = extract(_.request.uri.scheme)
}
