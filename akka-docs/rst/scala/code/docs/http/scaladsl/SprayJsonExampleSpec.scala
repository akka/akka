/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives
import org.scalatest.{ Matchers, WordSpec }

class SprayJsonExampleSpec extends WordSpec with Matchers {

  "spray-json example" in {
    //#example
    import spray.json._

    // domain model
    final case class Item(name: String, id: Long)
    final case class Order(items: List[Item])

    // collect your json format instances into a support trait:
    trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
      implicit val itemFormat = jsonFormat2(Item)
      implicit val orderFormat = jsonFormat1(Order) // contains List[Item]
    }

    // use it wherever json (un)marshalling is needed
    class MyJsonService extends Directives with JsonSupport {

      // format: OFF
      val route =
        get {
          pathSingleSlash {
            complete {
              Item("thing", 42) // will render as JSON
            }
          }
        } ~
        post {
          entity(as[Order]) { order => // will unmarshal JSON to Order
            val itemsCount = order.items.size
            val itemNames = order.items.map(_.name).mkString(", ")
            complete(s"Ordered $itemsCount items: $itemNames")
          }
        }
      // format: ON
      //#
    }
  }
}