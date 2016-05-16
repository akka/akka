/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.http.scaladsl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.scalatest.{Matchers, WordSpec}

class SprayJsonCompactMarshalSpec extends WordSpec with Matchers {

  "spray-json example" in {
    //#example
    import spray.json._

    // domain model
    final case class Item(name: String, id: Long)
    object Item extends DefaultJsonProtocol with SprayJsonSupport {
      implicit val printer = CompactPrinter
      implicit val itemFormat = jsonFormat2(Item.apply)
    }

    Item("Akka", 6).toJson
    // yields res0: spray.json.JsValue = {"name":"Akka","age":25}
  }
}
