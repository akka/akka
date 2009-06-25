package se.scalablesolutions.akka.api;

public class PersistentFailer {
  public int fail() {
    throw new RuntimeException("expected");
  }
}
