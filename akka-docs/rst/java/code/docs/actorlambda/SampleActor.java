/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actorlambda;

//#sample-actor
import akka.actor.AbstractActor;
import akka.japi.pf.ReceiveBuilder;

public class SampleActor extends AbstractActor {

  private Receive guarded = receiveBuilder()
    .match(String.class, s -> s.contains("guard"), s -> {
      sender().tell("contains(guard): " + s, self());
      getContext().unbecome();
    })
    .build();

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(Double.class, d -> {
        sender().tell(d.isNaN() ? 0 : d, self());
      })
      .match(Integer.class, i -> {
        sender().tell(i * 10, self());
      })
      .match(String.class, s -> s.startsWith("guard"), s -> {
        sender().tell("startsWith(guard): " + s.toUpperCase(), self());
        getContext().become(guarded, false);
      })
      .build();
  }
}
//#sample-actor
