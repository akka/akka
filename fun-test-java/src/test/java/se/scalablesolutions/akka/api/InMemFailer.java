package se.scalablesolutions.akka.api;

public class InMemFailer implements Serializable { 
  public int fail() {
    throw new RuntimeException("expected");
  }
}
