/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import spray.json.DefaultJsonProtocol
import headers._
import StatusCodes._

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

    Post("/", HttpEntity(`application/json`, """{ "name": "Jane", "favoriteNumber" : 42 }""")) ~>
      route ~> check {
        responseAs[String] shouldEqual "Person: Jane - favorite number: 42"
      }
  }

  "example-produce-with-json" in {
    import PersonJsonSupport._

    val findPerson = (f: Person => Unit) => {

      //... some processing logic...

      //complete the request
      f(Person("Jane", 42))
    }

    val route = get {
      produce(instanceOf[Person]) { completionFunction => ctx => findPerson(completionFunction) }
    }

    Get("/") ~> route ~> check {
      mediaType shouldEqual `application/json`
      responseAs[String] must contain(""""name": "Jane"""")
      responseAs[String] must contain(""""favoriteNumber": 42""")
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

    Post("/", HttpEntity(`application/json`, """{ "name": "Jane", "favoriteNumber" : 42 }""")) ~>
      route ~> check {
        mediaType shouldEqual `application/json`
        responseAs[String] must contain(""""name": "Jane"""")
        responseAs[String] must contain(""""favoriteNumber": 42""")
      }
  }
}
