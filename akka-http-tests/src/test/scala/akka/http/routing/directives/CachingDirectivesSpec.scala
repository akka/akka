/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

/*
import spray.routing.directives.CachingDirectives
import spray.util.SingletonException
import akka.http.model._
import headers.`Cache-Control`
import CacheDirectives._

class CachingDirectivesSpec extends RoutingSpec with CachingDirectives {
  sequential

  val countingService = {
    var i = 0
    cache(routeCache()) {
      complete {
        i += 1
        i.toString
      }
    }
  }
  val errorService = {
    var i = 0
    cache(routeCache()) {
      complete {
        i += 1
        HttpResponse(500 + i)
      }
    }
  }
  def prime(route: Route) = {
    route(RequestContext(HttpRequest(uri = Uri("http://example.com/")), system.deadLetters, Uri.Path.Empty).withDefaultSender(system.deadLetters))
    route
  }

  "the cacheResults directive" should {
    "return and cache the response of the first GET" in {
      Get() ~> countingService ~> check { responseAs[String] mustEqual "1" }
    }
    "return the cached response for a second GET" in {
      Get() ~> prime(countingService) ~> check { responseAs[String] mustEqual "1" }
    }
    "return the cached response also for HttpFailures on GETs" in {
      Get() ~> prime(errorService) ~> check { response mustEqual HttpResponse(501) }
    }
    "not cache responses for PUTs" in {
      Put() ~> prime(countingService) ~> check { responseAs[String] mustEqual "2" }
    }
    "not cache responses for GETs if the request contains a `Cache-Control: no-cache` header" in {
      Get() ~> addHeader(`Cache-Control`(`no-cache`)) ~> prime(countingService) ~> check { responseAs[String] mustEqual "3" }
    }
    "not cache responses for GETs if the request contains a `Cache-Control: max-age=0` header" in {
      Get() ~> addHeader(`Cache-Control`(`max-age`(0))) ~> prime(countingService) ~> check { responseAs[String] mustEqual "4" }
    }

    "be transparent to exceptions thrown from its inner route" in {
      case object MyException extends SingletonException
      implicit val myExceptionHandler = ExceptionHandler {
        case MyException ⇒ complete("Good")
      }

      Get() ~> cache(routeCache()) {
        _ ⇒ throw MyException // thrown directly
      } ~> check { responseAs[String] mustEqual "Good" }

      Get() ~> cache(routeCache()) {
        _.failWith(MyException) // bubbling up
      } ~> check { responseAs[String] mustEqual "Good" }
    }
  }

}*/
