/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdoc.akka.serialization.jackson.v2a;

import jdoc.akka.serialization.jackson.MySerializable;

// #rename-class
public class OrderPlaced implements MySerializable {
  public final String shoppingCartId;

  public OrderPlaced(String shoppingCartId) {
    this.shoppingCartId = shoppingCartId;
  }
}
// #rename-class
