/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.http.scaladsl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives
import org.scalatest.{Matchers, WordSpec}

class SprayJsonCompactMarshalSpec extends WordSpec with Matchers {

  "spray-json example" in {
    //#example
    import spray.json._

    // domain model
    final case class CompactPrintedItem(name: String, id: Long)
    object CompactPrintedItem extends DefaultJsonProtocol with SprayJsonSupport {
      implicit val printer = CompactPrinter
      implicit val itemFormat = jsonFormat2(CompactPrintedItem.apply)
    }

    // use it wherever json (un)marshalling is needed
    class MyJsonService extends Directives {

      // format: OFF
      val route =
        get {
          pathSingleSlash {
            complete {
              // should complete with spray.json.JsValue = {"name":"Akka","id":42}
              CompactPrintedItem("thing", 42) // will render as JSON
            }
          }
        }
      // format: ON
      //#
    }
  }
}
