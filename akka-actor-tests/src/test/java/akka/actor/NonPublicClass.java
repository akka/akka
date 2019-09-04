/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor;

public class NonPublicClass {
  public static Props createProps() {
    return Props.create(MyNonPublicActorClass.class);
  }
}

class MyNonPublicActorClass extends ClassicAbstractActor {
  @Override
  public void onReceive(Object msg) {
    getSender().tell(msg, getSelf());
  }
}
