/*
 * Copyright (C) 2019-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package jdoc.akka.serialization.jackson.v2a;

import akka.serialization.jackson.JsonSerializable;
import com.fasterxml.jackson.annotation.JsonCreator;

// #rename-class
public class OrderPlaced implements JsonSerializable {
  public final String shoppingCartId;

  @JsonCreator
  public OrderPlaced(String shoppingCartId) {
    this.shoppingCartId = shoppingCartId;
  }
}
// #rename-class
