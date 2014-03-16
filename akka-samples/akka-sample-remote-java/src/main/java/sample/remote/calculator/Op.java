package sample.remote.calculator;

import java.io.Serializable;

public class Op {

  public interface MathOp extends Serializable {
  }

  public interface MathResult extends Serializable {
  }

  static class Add implements MathOp {
    private static final long serialVersionUID = 1L;
    private final int n1;
    private final int n2;

    public Add(int n1, int n2) {
      this.n1 = n1;
      this.n2 = n2;
    }

    public int getN1() {
      return n1;
    }

    public int getN2() {
      return n2;
    }
  }

  static class AddResult implements MathResult {
    private static final long serialVersionUID = 1L;
    private final int n1;
    private final int n2;
    private final int result;

    public AddResult(int n1, int n2, int result) {
      this.n1 = n1;
      this.n2 = n2;
      this.result = result;
    }

    public int getN1() {
      return n1;
    }

    public int getN2() {
      return n2;
    }

    public int getResult() {
      return result;
    }
  }

  static class Subtract implements MathOp {
    private static final long serialVersionUID = 1L;
    private final int n1;
    private final int n2;

    public Subtract(int n1, int n2) {
      this.n1 = n1;
      this.n2 = n2;
    }

    public int getN1() {
      return n1;
    }

    public int getN2() {
      return n2;
    }
  }

  static class SubtractResult implements MathResult {
    private static final long serialVersionUID = 1L;
    private final int n1;
    private final int n2;
    private final int result;

    public SubtractResult(int n1, int n2, int result) {
      this.n1 = n1;
      this.n2 = n2;
      this.result = result;
    }

    public int getN1() {
      return n1;
    }

    public int getN2() {
      return n2;
    }

    public int getResult() {
      return result;
    }
  }

  static class Multiply implements MathOp {
    private static final long serialVersionUID = 1L;
    private final int n1;
    private final int n2;

    public Multiply(int n1, int n2) {
      this.n1 = n1;
      this.n2 = n2;
    }

    public int getN1() {
      return n1;
    }

    public int getN2() {
      return n2;
    }
  }

  static class MultiplicationResult implements MathResult {
    private static final long serialVersionUID = 1L;
    private final int n1;
    private final int n2;
    private final int result;

    public MultiplicationResult(int n1, int n2, int result) {
      this.n1 = n1;
      this.n2 = n2;
      this.result = result;
    }

    public int getN1() {
      return n1;
    }

    public int getN2() {
      return n2;
    }

    public int getResult() {
      return result;
    }
  }

  static class Divide implements MathOp {
    private static final long serialVersionUID = 1L;
    private final double n1;
    private final int n2;

    public Divide(double n1, int n2) {
      this.n1 = n1;
      this.n2 = n2;
    }

    public double getN1() {
      return n1;
    }

    public int getN2() {
      return n2;
    }
  }

  static class DivisionResult implements MathResult {
    private static final long serialVersionUID = 1L;
    private final double n1;
    private final int n2;
    private final double result;

    public DivisionResult(double n1, int n2, double result) {
      this.n1 = n1;
      this.n2 = n2;
      this.result = result;
    }

    public double getN1() {
      return n1;
    }

    public int getN2() {
      return n2;
    }

    public double getResult() {
      return result;
    }
  }
}
