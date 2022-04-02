/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package doc.akka.serialization.jackson.v1

import doc.akka.serialization.jackson.MySerializable

// #add-optional
// #forward-one-rename
case class ItemAdded(shoppingCartId: String, productId: String, quantity: Int) extends MySerializable
// #forward-one-rename
// #add-optional
