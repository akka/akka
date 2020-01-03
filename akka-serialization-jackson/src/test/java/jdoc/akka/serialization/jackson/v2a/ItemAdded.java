/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdoc.akka.serialization.jackson.v2a;

import com.fasterxml.jackson.annotation.JsonCreator;
import jdoc.akka.serialization.jackson.MySerializable;

import java.util.Optional;

// #add-optional
public class ItemAdded implements MySerializable {
  public final String shoppingCartId;
  public final String productId;
  public final int quantity;
  public final Optional<Double> discount;
  public final String note;

  @JsonCreator
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

    // default for note is "" if not included in json
    if (note == null) this.note = "";
    else this.note = note;
  }

  public ItemAdded(
      String shoppingCartId, String productId, int quantity, Optional<Double> discount) {
    this(shoppingCartId, productId, quantity, discount, "");
  }
}
// #add-optional
