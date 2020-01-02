/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdoc.akka.serialization.jackson.v1;

import com.fasterxml.jackson.annotation.JsonCreator;
import jdoc.akka.serialization.jackson.MySerializable;

// #rename-class
public class OrderAdded implements MySerializable {
  public final String shoppingCartId;

  @JsonCreator
  public OrderAdded(String shoppingCartId) {
    this.shoppingCartId = shoppingCartId;
  }
}
// #rename-class
