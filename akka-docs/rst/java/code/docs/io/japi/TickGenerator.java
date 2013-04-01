/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io.japi;

import java.util.Collections;

import scala.concurrent.duration.Deadline;
import scala.concurrent.duration.FiniteDuration;
import scala.util.Either;
import akka.actor.ActorSystem;
import akka.io.AbstractPipePair;
import akka.io.PipePair;
import akka.io.PipePairFactory;
import akka.io.PipelineStage;

//#tick-generator
public class TickGenerator<Cmd, Evt> extends
    PipelineStage<HasActorContext, Cmd, Cmd, Evt, Evt> {

  public static interface Trigger {};
  
  public static class Tick implements Trigger {
    final FiniteDuration timestamp;

    public Tick(FiniteDuration timestamp) {
      super();
      this.timestamp = timestamp;
    }

    public FiniteDuration getTimestamp() {
      return timestamp;
    }
  }

  private final FiniteDuration interval;

  public TickGenerator(FiniteDuration interval) {
    this.interval = interval;
  }

  @Override
  public PipePair<Cmd, Cmd, Evt, Evt> apply(final HasActorContext ctx) {
    return PipePairFactory.create(ctx,
        new AbstractPipePair<Cmd, Cmd, Evt, Evt>() {
      
          private final Trigger trigger = new Trigger() {
            public String toString() {
              return "Tick[" + ctx.getContext().self().path() + "]";
            }
          };
          
          private void schedule() {
            final ActorSystem system = ctx.getContext().system();
            system.scheduler().scheduleOnce(interval,
                ctx.getContext().self(), trigger, system.dispatcher(), null);
          }

          {
            schedule();
          }

          @Override
          public Iterable<Either<Evt, Cmd>> onCommand(Cmd cmd) {
            return singleCommand(cmd);
          }

          @Override
          public Iterable<Either<Evt, Cmd>> onEvent(Evt evt) {
            return singleEvent(evt);
          }

          @Override
          public Iterable<Either<Evt, Cmd>> onManagementCommand(Object cmd) {
            if (cmd == trigger) {
              ctx.getContext().self().tell(new Tick(Deadline.now().time()), null);
              schedule();
            }
            return Collections.emptyList();
          }

        });
  }
}
//#tick-generator
