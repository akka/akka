/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model.Uri
import docs.http.scaladsl.server.RoutingSpec
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.headers.CacheDirectives._
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.directives.CachingDirectives
import akka.http.scaladsl.model.HttpMethods.GET

class CachingDirectivesExamplesSpec extends RoutingSpec with CachingDirectives {

  "cache" in {
    //#cache
    //Example keyer for non-authenticated GET requests
    val simpleKeyer: PartialFunction[RequestContext, Uri] = {
      val isGet: RequestContext ⇒ Boolean = _.request.method == GET
      val isAuthorized: RequestContext ⇒ Boolean = _.request.headers.exists(_.is(Authorization.lowercaseName))
      PartialFunction {
        case r: RequestContext if isGet(r) && !isAuthorized(r) ⇒ r.request.uri
      }
    }

    var i = 0
    val route =
      cache(routeCache(), simpleKeyer) {
        complete {
          i += 1
          i.toString
        }
      }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    // now cached
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    // caching prevented
    Get("/") ~> `Cache-Control`(`no-cache`) ~> route ~> check {
      responseAs[String] shouldEqual "2"
    }
    //#cache
  }
  "always-cache" in {
    //#always-cache
    //Example keyer for non-authenticated GET requests
    val simpleKeyer: PartialFunction[RequestContext, Uri] = {
      val isGet: RequestContext ⇒ Boolean = _.request.method == GET
      val isAuthorized: RequestContext ⇒ Boolean = _.request.headers.exists(_.is(Authorization.lowercaseName))
      PartialFunction {
        case r: RequestContext if isGet(r) && !isAuthorized(r) ⇒ r.request.uri
      }
    }

    var i = 0
    val route =
      alwaysCache(routeCache(), simpleKeyer) {
        complete {
          i += 1
          i.toString
        }
      }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    // now cached
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    Get("/") ~> `Cache-Control`(`no-cache`) ~> route ~> check {
      responseAs[String] shouldEqual "1"
    }
    //#always-cache
  }
  "cachingProhibited" in {
    //#caching-prohibited
    val route =
      cachingProhibited {
        complete("abc")
      }

    Get("/") ~> route ~> check {
      handled shouldEqual false
    }
    Get("/") ~> `Cache-Control`(`no-cache`) ~> route ~> check {
      responseAs[String] shouldEqual "abc"
    }
    //#caching-prohibited
  }
}
