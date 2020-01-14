/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package doc.akka.serialization.jackson.v2a

import doc.akka.serialization.jackson.MySerializable

// #rename-class
case class OrderPlaced(shoppingCartId: String) extends MySerializable
// #rename-class
