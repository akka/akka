/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package doc.akka.serialization.jackson.v1

import doc.akka.serialization.jackson.MySerializable

// #add-optional
case class ItemAdded(shoppingCartId: String, productId: String, quantity: Int) extends MySerializable
// #add-optional
