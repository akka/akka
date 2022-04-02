/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdoc.akka.serialization.jackson.v2a;

import jdoc.akka.serialization.jackson.MySerializable;

import java.util.Optional;

// #structural
public class Customer implements MySerializable {
  public final String name;
  public final Address shippingAddress;
  public final Optional<Address> billingAddress;

  public Customer(String name, Address shippingAddress, Optional<Address> billingAddress) {
    this.name = name;
    this.shippingAddress = shippingAddress;
    this.billingAddress = billingAddress;
  }
}
// #structural
