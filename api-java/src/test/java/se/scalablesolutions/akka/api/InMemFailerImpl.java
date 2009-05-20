package se.scalablesolutions.akka.api;

public class InMemFailerImpl implements InMemFailer {
  public void fail() {
    throw new RuntimeException("expected");
  }
}
