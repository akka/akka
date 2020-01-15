/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package doc.akka.serialization.jackson.v2a

// #structural
import akka.serialization.jackson.JacksonMigration
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

class CustomerMigration extends JacksonMigration {

  override def currentVersion: Int = 2

  override def transform(fromVersion: Int, json: JsonNode): JsonNode = {
    val root = json.asInstanceOf[ObjectNode]
    if (fromVersion <= 1) {
      val shippingAddress = root.`with`("shippingAddress")
      shippingAddress.set("street", root.get("street"))
      shippingAddress.set("city", root.get("city"))
      shippingAddress.set("zipCode", root.get("zipCode"))
      shippingAddress.set("country", root.get("country"))
      root.remove("street")
      root.remove("city")
      root.remove("zipCode")
      root.remove("country")
    }
    root
  }
}
// #structural
