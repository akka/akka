package se.scalablesolutions.akka.api;

public class InMemFailer implements java.io.Serializable { 
  public int fail() {
    throw new RuntimeException("Expected exception; to test fault-tolerance");
  }
}
