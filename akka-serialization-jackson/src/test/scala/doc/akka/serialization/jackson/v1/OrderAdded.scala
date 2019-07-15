/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package doc.akka.serialization.jackson.v1

import doc.akka.serialization.jackson.MySerializable

// #rename-class
case class OrderAdded(shoppingCartId: String) extends MySerializable
// #rename-class
