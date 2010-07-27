package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;

public class TypedActorFailerImpl extends TypedActor implements TypedActorFailer { 
  public int fail() {
    throw new RuntimeException("expected");
  }
}
