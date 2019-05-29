/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdoc.akka.serialization.jackson.v1;

import jdoc.akka.serialization.jackson.MySerializable;

// #rename-class
public class OrderAdded implements MySerializable {
  public final String shoppingCartId;

  public OrderAdded(String shoppingCartId) {
    this.shoppingCartId = shoppingCartId;
  }
}
// #rename-class
