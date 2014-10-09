/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

trait SchemeDirectives {
  import BasicDirectives._

  /**
   * Extracts the Uri scheme from the request.
   */
  def schemeName: Directive1[String] = SchemeDirectives._schemeName

  /**
   * Rejects all requests whose Uri scheme does not match the given one.
   */
  def scheme(name: String): Directive0 =
    schemeName.require(_ == name, SchemeRejection(name)) & cancelRejections(classOf[SchemeRejection])
}

object SchemeDirectives extends SchemeDirectives {
  import BasicDirectives._

  private val _schemeName: Directive1[String] = extract(_.request.uri.scheme)
}
