package se.scalablesolutions.akka.api;

public class PersistentFailer implements java.io.Serializable {
  public int fail() {
    throw new RuntimeException("expected");
  }
}
