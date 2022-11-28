/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.cluster;

import java.math.BigInteger;
import java.io.Serializable;

public class FactorialResult implements Serializable {
  private static final long serialVersionUID = 1L;
  public final int n;
  public final BigInteger factorial;

  FactorialResult(int n, BigInteger factorial) {
    this.n = n;
    this.factorial = factorial;
  }
}
