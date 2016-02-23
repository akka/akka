/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import docs.http.scaladsl.server.RoutingSpec
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model._
import spray.json.{ JsValue, DefaultJsonProtocol }

//# person-case-class
case class Person(name: String, favoriteNumber: Int)

//# person-json-support
object PersonJsonSupport extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val PortofolioFormats = jsonFormat2(Person)
}
//#

class MarshallingDirectivesExamplesSpec extends RoutingSpec {

  "example-entity-with-json" in {
    import PersonJsonSupport._

    val route = post {
      entity(as[Person]) { person =>
        complete(s"Person: ${person.name} - favorite number: ${person.favoriteNumber}")
      }
    }

    // tests:
    Post("/", HttpEntity(`application/json`, """{ "name": "Jane", "favoriteNumber" : 42 }""")) ~>
      route ~> check {
        responseAs[String] shouldEqual "Person: Jane - favorite number: 42"
      }
  }

  "example-entity-with-raw-json" in {
    import PersonJsonSupport._

    val route = post {
      entity(as[JsValue]) { json =>
        complete(s"Person: ${json.asJsObject.fields("name")} - favorite number: ${json.asJsObject.fields("favoriteNumber")}")
      }
    }

    // tests:
    Post("/", HttpEntity(`application/json`, """{ "name": "Jane", "favoriteNumber" : 42 }""")) ~>
      route ~> check {
        responseAs[String] shouldEqual """Person: "Jane" - favorite number: 42"""
      }
  }

  "example-completeWith-with-json" in {
    import PersonJsonSupport._

    val findPerson = (f: Person => Unit) => {

      //... some processing logic...

      //complete the request
      f(Person("Jane", 42))
    }

    val route = get {
      completeWith(instanceOf[Person]) { completionFunction => findPerson(completionFunction) }
    }

    // tests:
    Get("/") ~> route ~> check {
      mediaType shouldEqual `application/json`
      responseAs[String] should include(""""name": "Jane"""")
      responseAs[String] should include(""""favoriteNumber": 42""")
    }
  }

  "example-handleWith-with-json" in {
    import PersonJsonSupport._

    val updatePerson = (person: Person) => {

      //... some processing logic...

      //return the person
      person
    }

    val route = post {
      handleWith(updatePerson)
    }

    // tests:
    Post("/", HttpEntity(`application/json`, """{ "name": "Jane", "favoriteNumber" : 42 }""")) ~>
      route ~> check {
        mediaType shouldEqual `application/json`
        responseAs[String] should include(""""name": "Jane"""")
        responseAs[String] should include(""""favoriteNumber": 42""")
      }
  }
}
