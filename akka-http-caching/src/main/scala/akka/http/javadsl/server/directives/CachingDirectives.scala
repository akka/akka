/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.http.javadsl.server.directives

import java.util.function.Supplier

import akka.annotation.ApiMayChange
import akka.http.caching.javadsl.{ Cache, CachingSettings }
import akka.http.caching.{ CacheJavaMapping, LfuCache }
import akka.http.impl.util.JavaMapping
import akka.http.javadsl.server.{ RequestContext, Route, RouteResult }

@ApiMayChange
class CachingDirectives {

  import akka.http.scaladsl.server.directives.{ CachingDirectives ⇒ D }

  private implicit def routeResultCacheMapping[K] =
    CacheJavaMapping.cacheMapping[K, RouteResult, K, akka.http.scaladsl.server.RouteResult]

  /**
   * Wraps its inner Route with caching support using the given [[akka.http.caching.scaladsl.Cache]] implementation and
   * keyer function.
   *
   * Use [[akka.japi.JavaPartialFunction]] to build the `keyer`.
   */
  def cache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K], inner: Supplier[Route]) = RouteAdapter {
    D.cache(
      JavaMapping.toScala(cache),
      toScalaKeyer(keyer)
    ) { inner.get.delegate }
  }

  private def toScalaKeyer[K](keyer: PartialFunction[RequestContext, K]): PartialFunction[akka.http.scaladsl.server.RequestContext, K] = {
    PartialFunction {
      (scalaRequestContext: akka.http.scaladsl.server.RequestContext) ⇒
        {
          val javaRequestContext = akka.http.javadsl.server.RoutingJavaMapping.RequestContext.toJava(scalaRequestContext)
          keyer(javaRequestContext)
        }
    }
  }

  /**
   * Passes only requests to the inner route that explicitly forbid caching with a `Cache-Control` header with either
   * a `no-cache` or `max-age=0` setting.
   */
  def cachingProhibited(inner: Supplier[Route]) = RouteAdapter {
    D.cachingProhibited { inner.get.delegate }
  }

  /**
   * Wraps its inner Route with caching support using the given [[Cache]] implementation and
   * keyer function. Note that routes producing streaming responses cannot be wrapped with this directive.
   */
  def alwaysCache[K](cache: Cache[K, RouteResult], keyer: PartialFunction[RequestContext, K], inner: Supplier[Route]) = RouteAdapter {
    D.alwaysCache(
      cache.asInstanceOf[akka.http.caching.scaladsl.Cache[K, akka.http.scaladsl.server.RouteResult]],
      toScalaKeyer(keyer)
    ) { inner.get.delegate }
  }

  /**
   * Creates an [[LfuCache]]
   *
   * Default settings are available via [[akka.http.caching.javadsl.CachingSettings.create]].
   */
  def routeCache[K](settings: CachingSettings): Cache[K, RouteResult] =
    JavaMapping.toJava(D.routeCache[K](settings))
}

object CachingDirectives extends CachingDirectives
