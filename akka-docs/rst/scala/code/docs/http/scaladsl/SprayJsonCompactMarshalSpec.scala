/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.http.scaladsl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives
import org.scalatest.{ Matchers, WordSpec }

class SprayJsonCompactMarshalSpec extends WordSpec with Matchers {

  "spray-json example" in {
    //#example
    import spray.json._

    // domain model
    final case class CompactPrintedItem(name: String, id: Long)

    trait CompactJsonFormatSupport extends DefaultJsonProtocol with SprayJsonSupport {
      implicit val printer = CompactPrinter
      implicit val compactPrintedItemFormat = jsonFormat2(CompactPrintedItem)
    }

    // use it wherever json (un)marshalling is needed
    class MyJsonService extends Directives with CompactJsonFormatSupport {

      // format: OFF
      val route =
        get {
          pathSingleSlash {
            complete {
              // should complete with spray.json.JsValue = {"name":"akka","id":42}
              CompactPrintedItem("akka", 42) // will render as JSON
            }
          }
        }
      // format: ON
      //#
    }
  }
}
