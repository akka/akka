package se.scalablesolutions.akka.actor;

public class ActiveObjectFailer implements java.io.Serializable { 
  public int fail() {
    throw new RuntimeException("Expected exception; to test fault-tolerance");
  }
}
