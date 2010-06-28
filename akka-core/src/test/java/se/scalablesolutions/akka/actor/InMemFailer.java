package se.scalablesolutions.akka.actor;

public class InMemFailer implements java.io.Serializable { 
  public int fail() {
    throw new RuntimeException("expected");
  }
}
