package sample.cluster.factorial.japi;

import java.math.BigInteger;
import java.io.Serializable;

public class FactorialResult implements Serializable {
  public final int n;
  public final BigInteger factorial;

  FactorialResult(int n, BigInteger factorial) {
    this.n = n;
    this.factorial = factorial;
  }
}