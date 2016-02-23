/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

trait SchemeDirectives {
  import BasicDirectives._

  /**
   * Extracts the Uri scheme from the request.
   */
  def extractScheme: Directive1[String] = SchemeDirectives._extractScheme

  /**
   * Rejects all requests whose Uri scheme does not match the given one.
   */
  def scheme(name: String): Directive0 =
    extractScheme.require(_ == name, SchemeRejection(name)) & cancelRejections(classOf[SchemeRejection])
}

object SchemeDirectives extends SchemeDirectives {
  import BasicDirectives._

  private val _extractScheme: Directive1[String] = extract(_.request.uri.scheme)
}
