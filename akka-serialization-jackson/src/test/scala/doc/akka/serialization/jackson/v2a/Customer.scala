/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package doc.akka.serialization.jackson.v2a

import doc.akka.serialization.jackson.MySerializable

// #structural
case class Customer(name: String, shippingAddress: Address, billingAddress: Option[Address]) extends MySerializable
// #structural
