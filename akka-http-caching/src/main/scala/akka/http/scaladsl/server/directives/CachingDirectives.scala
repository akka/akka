/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.http.scaladsl.server.directives

import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.http.caching.scaladsl.{ Cache, CachingSettingsImpl }
import akka.http.caching.javadsl.{ CachingSettings }
import akka.http.caching.LfuCache
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.headers.CacheDirectives._

@ApiMayChange
trait CachingDirectives {
  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.RouteDirectives._

  /**
   * Wraps its inner Route with caching support using the given [[Cache]] implementation and
   * keyer function.
   */
  def cache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K]): Directive0 =
    cachingProhibited | alwaysCache(cache, keyer)

  /**
   * Passes only requests to the inner route that explicitly forbid caching with a `Cache-Control` header with either
   * a `no-cache` or `max-age=0` setting.
   */
  def cachingProhibited: Directive0 =
    extract(_.request.headers.exists {
      case x: `Cache-Control` ⇒ x.directives.exists {
        case `no-cache`   ⇒ true
        case `max-age`(0) ⇒ true
        case _            ⇒ false
      }
      case _ ⇒ false
    }).flatMap(if (_) pass else reject)

  /**
   * Wraps its inner Route with caching support using the given [[Cache]] implementation and
   * keyer function. Note that routes producing streaming responses cannot be wrapped with this directive.
   */
  def alwaysCache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K]): Directive0 = {
    mapInnerRoute { route ⇒ ctx ⇒
      keyer.lift(ctx) match {
        case Some(key) ⇒ cache.apply(key, () ⇒ route(ctx))
        case None      ⇒ route(ctx)
      }
    }
  }

  /**
   * Creates an [[LfuCache]] with default settings obtained from the systems' configuration
   */
  @ApiMayChange // since perhaps we indeed have to hardcode the defaults in code rather than reference.conf?
  def routeCache[K](implicit s: ActorSystem): Cache[K, RouteResult] =
    LfuCache[K, RouteResult](akka.http.caching.scaladsl.CachingSettings(s))

  /**
   * Creates an [[LfuCache]].
   * Default settings are available via [[akka.http.caching.scaladsl.CachingSettings.apply]].
   */
  def routeCache[K](settings: CachingSettings): Cache[K, RouteResult] =
    LfuCache[K, RouteResult](settings)
}

object CachingDirectives extends CachingDirectives
