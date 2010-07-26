package se.scalablesolutions.akka.actor;

public class TypedActorFailer implements java.io.Serializable { 
  public int fail() {
    throw new RuntimeException("expected");
  }
}
