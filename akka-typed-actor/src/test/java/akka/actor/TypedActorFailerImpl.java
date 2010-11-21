package akka.actor;

import akka.actor.*;

public class TypedActorFailerImpl extends TypedActor implements TypedActorFailer {
  public int fail() {
    throw new RuntimeException("expected");
  }
}
