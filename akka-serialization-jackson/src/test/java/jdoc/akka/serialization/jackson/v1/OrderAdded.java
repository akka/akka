/*
 * Copyright (C) 2019-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package jdoc.akka.serialization.jackson.v1;

import akka.serialization.jackson.JsonSerializable;
import com.fasterxml.jackson.annotation.JsonCreator;

// #rename-class
public class OrderAdded implements JsonSerializable {
  public final String shoppingCartId;

  @JsonCreator
  public OrderAdded(String shoppingCartId) {
    this.shoppingCartId = shoppingCartId;
  }
}
// #rename-class
