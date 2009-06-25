package se.scalablesolutions.akka.api;

public class InMemFailer { 
  public int fail() {
    throw new RuntimeException("expected");
  }
}
