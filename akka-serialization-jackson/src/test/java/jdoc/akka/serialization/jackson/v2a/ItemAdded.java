/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdoc.akka.serialization.jackson.v2a;

import jdoc.akka.serialization.jackson.MySerializable;

import java.util.Optional;

// #add-optional
public class ItemAdded implements MySerializable {
  public final String shoppingCartId;
  public final String productId;
  public final int quantity;
  public final Optional<Double> discount;
  public final String note;

  public ItemAdded(
      String shoppingCartId,
      String productId,
      int quantity,
      Optional<Double> discount,
      String note) {
    this.shoppingCartId = shoppingCartId;
    this.productId = productId;
    this.quantity = quantity;
    this.discount = discount;
    this.note = note;
  }

  public ItemAdded(
      String shoppingCartId, String productId, int quantity, Optional<Double> discount) {
    this(shoppingCartId, productId, quantity, discount, "");
  }
}
// #add-optional
