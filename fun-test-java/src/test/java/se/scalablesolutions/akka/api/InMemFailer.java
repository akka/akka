package se.scalablesolutions.akka.api;

public class InMemFailer { 
  public void fail() {
    throw new RuntimeException("expected");
  }
}
