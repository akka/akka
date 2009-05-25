package se.scalablesolutions.akka.api;

public class PersistentFailer {
  public void fail() {
    throw new RuntimeException("expected");
  }
}
