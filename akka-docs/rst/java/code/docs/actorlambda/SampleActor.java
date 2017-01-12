/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actorlambda;

//#sample-actor
import akka.actor.AbstractActor;
import akka.japi.pf.ReceiveBuilder;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

public class SampleActor extends AbstractActor {

  private PartialFunction<Object, BoxedUnit> guarded = ReceiveBuilder.
    match(String.class, s -> s.contains("guard"), s -> {
      sender().tell("contains(guard): " + s, self());
      context().unbecome();
    }).build();

  public SampleActor() {
    receive(ReceiveBuilder.
      match(Double.class, d -> {
        sender().tell(d.isNaN() ? 0 : d, self());
      }).
      match(Integer.class, i -> {
        sender().tell(i * 10, self());
      }).
      match(String.class, s -> s.startsWith("guard"), s -> {
        sender().tell("startsWith(guard): " + s.toUpperCase(), self());
        context().become(guarded, false);
      }).build()
    );
  }
}
//#sample-actor
