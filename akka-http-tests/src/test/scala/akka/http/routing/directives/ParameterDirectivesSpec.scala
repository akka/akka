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

import akka.http.unmarshalling.Deserializer
import akka.shapeless._
import akka.http.routing._
import org.scalatest.Inside

class ParameterDirectivesSpec extends RoutingSpec with Inside {
  "tests" should {
    "be resurrected" in pending
  }
  "when used with 'as[Int]' the parameter directive" should {
    "extract a parameter value as Int (using the general `parameters` directive)" in pendingUntilFixed {
      Get("/?amount=123") ~> {
        parameters('amount.as[Int] :: HNil) { echoComplete }
      } ~> check { responseAs[String] mustEqual "123" }
    }
    "extract a parameter values as Int (using the `parameter` directive)" in pendingUntilFixed {
      Get("/?amount=123") ~> {
        parameter('amount.as[Int]) { echoComplete }
      } ~> check { responseAs[String] mustEqual "123" }
    }
    "cause a MalformedQueryParamRejection on illegal Int values" in pendingUntilFixed {
      Get("/?amount=1x3") ~> {
        parameter('amount.as[Int]) { echoComplete }
      } ~> check {
        inside(rejection) {
          case MalformedQueryParamRejection("amount", "'1x3' is not a valid 32-bit integer value", Some(_)) ⇒
        }
      }
    }
    "supply typed default values" in pendingUntilFixed {
      Get() ~> {
        parameter('amount ? 45) { echoComplete }
      } ~> check { responseAs[String] mustEqual "45" }
    }
    "create typed optional parameters that" in pendingUntilFixed {
      "extract Some(value) when present" in pendingUntilFixed {
        Get("/?amount=12") ~> {
          parameter("amount".as[Int]?) { echoComplete }
        } ~> check { responseAs[String] mustEqual "Some(12)" }
      }
      "extract None when not present" in pendingUntilFixed {
        Get() ~> {
          parameter("amount".as[Int]?) { echoComplete }
        } ~> check { responseAs[String] mustEqual "None" }
      }
      "cause a MalformedQueryParamRejection on illegal Int values" in pendingUntilFixed {
        Get("/?amount=x") ~> {
          parameter("amount".as[Int]?) { echoComplete }
        } ~> check {
          inside(rejection) {
            case MalformedQueryParamRejection("amount", "'x' is not a valid 32-bit integer value", Some(_)) ⇒
          }
        }
      }
    }
  }

  "when used with 'as(HexInt)' the parameter directive" should {
    import akka.http.unmarshalling.FromStringDeserializers.HexInt
    "extract parameter values as Int" in pendingUntilFixed {
      Get("/?amount=1f") ~> {
        parameter('amount.as(HexInt)) { echoComplete }
      } ~> check { responseAs[String] mustEqual "31" }
    }
    "cause a MalformedQueryParamRejection on illegal Int values" in pendingUntilFixed {
      Get("/?amount=1x3") ~> {
        parameter('amount.as(HexInt)) { echoComplete }
      } ~> check {
        inside(rejection) {
          case MalformedQueryParamRejection("amount",
            "'1x3' is not a valid 32-bit hexadecimal integer value", Some(_)) ⇒
        }
      }
    }
    "supply typed default values" in pendingUntilFixed {
      Get() ~> {
        parameter('amount.as(HexInt) ? 45) { echoComplete }
      } ~> check { responseAs[String] mustEqual "45" }
    }
    "create typed optional parameters that" in pending /*{
      "extract Some(value) when present" in pendingUntilFixed {
        Get("/?amount=A") ~> {
          parameter("amount".as(HexInt)?) { echoComplete }
        } ~> check { responseAs[String] mustEqual "Some(10)" }
      }
      "extract None when not present" in pendingUntilFixed {
        Get() ~> {
          parameter("amount".as(HexInt)?) { echoComplete }
        } ~> check { responseAs[String] mustEqual "None" }
      }
      "cause a MalformedQueryParamRejection on illegal Int values" in pendingUntilFixed {
        Get("/?amount=x") ~> {
          parameter("amount".as(HexInt)?) { echoComplete }
        } ~> check {
          inside(rejection) {
            case MalformedQueryParamRejection("amount",
              "'x' is not a valid 32-bit hexadecimal integer value", Some(_)) ⇒
          }
        }
      }
    }*/
  }

  "The 'parameters' extraction directive" should {
    "extract the value of given parameters" in pendingUntilFixed {
      Get("/?name=Parsons&FirstName=Ellen") ~> {
        parameters("name", 'FirstName) { (name, firstName) ⇒
          complete(firstName + name)
        }
      } ~> check { responseAs[String] mustEqual "EllenParsons" }
    }
    "correctly extract an optional parameter" in pendingUntilFixed {
      Get("/?foo=bar") ~> parameters('foo ?) { echoComplete } ~> check { responseAs[String] mustEqual "Some(bar)" }
      Get("/?foo=bar") ~> parameters('baz ?) { echoComplete } ~> check { responseAs[String] mustEqual "None" }
    }
    "ignore additional parameters" in pendingUntilFixed {
      Get("/?name=Parsons&FirstName=Ellen&age=29") ~> {
        parameters("name", 'FirstName) { (name, firstName) ⇒
          complete(firstName + name)
        }
      } ~> check { responseAs[String] mustEqual "EllenParsons" }
    }
    "reject the request with a MissingQueryParamRejection if a required parameters is missing" in pendingUntilFixed {
      Get("/?name=Parsons&sex=female") ~> {
        parameters('name, 'FirstName, 'age) { (name, firstName, age) ⇒
          completeOk
        }
      } ~> check { rejection mustEqual MissingQueryParamRejection("FirstName") }
    }
    "supply the default value if an optional parameter is missing" in pendingUntilFixed {
      Get("/?name=Parsons&FirstName=Ellen") ~> {
        parameters("name"?, 'FirstName, 'age ? "29", 'eyes?) { (name, firstName, age, eyes) ⇒
          complete(firstName + name + age + eyes)
        }
      } ~> check { responseAs[String] mustEqual "EllenSome(Parsons)29None" }
    }
    "supply the default value if an optional parameter is missing (with the general `parameters` directive)" in pendingUntilFixed {
      Get("/?name=Parsons&FirstName=Ellen") ~> {
        parameters(("name"?) :: 'FirstName :: ('age ? "29") :: ('eyes?) :: HNil) { (name, firstName, age, eyes) ⇒
          complete(firstName + name + age + eyes)
        }
      } ~> check { responseAs[String] mustEqual "EllenSome(Parsons)29None" }
    }
  }

  "The 'parameter' requirement directive" should {
    "block requests that do not contain the required parameter" in pendingUntilFixed {
      Get("/person?age=19") ~> {
        parameter('nose ! "large") { completeOk }
      } ~> check { handled mustEqual (false) }
    }
    "block requests that contain the required parameter but with an unmatching value" in pendingUntilFixed {
      Get("/person?age=19&nose=small") ~> {
        parameter('nose ! "large") { completeOk }
      } ~> check { handled mustEqual (false) }
    }
    "let requests pass that contain the required parameter with its required value" in pendingUntilFixed {
      Get("/person?nose=large&eyes=blue") ~> {
        parameter('nose ! "large") { completeOk }
      } ~> check { response mustEqual Ok }
    }
    "be useable for method tunneling" in pendingUntilFixed {
      val route = {
        (post | parameter('method ! "post")) { complete("POST") } ~
          get { complete("GET") }
      }
      Get("/?method=post") ~> route ~> check { responseAs[String] mustEqual "POST" }
      Post() ~> route ~> check { responseAs[String] mustEqual "POST" }
      Get() ~> route ~> check { responseAs[String] mustEqual "GET" }
    }
  }
}