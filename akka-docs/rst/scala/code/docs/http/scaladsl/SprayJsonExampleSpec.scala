/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.Future

class SprayJsonExampleSpec extends WordSpec with Matchers {

  def compileOnlySpec(body: => Unit) = ()

  "spray-json example" in compileOnlySpec {
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
            complete(Item("thing", 42)) // will render as JSON
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

  "second-spray-json-example" in compileOnlySpec {
    //#second-spray-json-example
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.Done
    import akka.http.scaladsl.server.Route
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.model.StatusCodes
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import spray.json.DefaultJsonProtocol._

    object WebServer {

      // domain model
      final case class Item(name: String, id: Long)
      final case class Order(items: List[Item])

      // formats for unmarshalling and marshalling
      implicit val itemFormat = jsonFormat2(Item)
      implicit val orderFormat = jsonFormat1(Order)

      // (fake) async database query api
      def fetchItem(itemId: Long): Future[Option[Item]] = ???
      def saveOrder(order: Order): Future[Done] = ???

      def main(args: Array[String]) {

        // needed to run the route
        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()

        val route: Route =
          get {
            pathPrefix("item" / LongNumber) { id =>
              // there might be no item for a given id
              val maybeItem: Future[Option[Item]] = fetchItem(id)

              onSuccess(maybeItem) {
                case Some(item) => complete(item)
                case None       => complete(StatusCodes.NotFound)
              }
            }
          } ~
            post {
              path("create-order") {
                entity(as[Order]) { order =>
                  val saved: Future[Done] = saveOrder(order)
                  onComplete(saved) { done =>
                    complete("order created")
                  }
                }
              }
            }

      }
    }
    //#second-spray-json-example
  }
}