/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com/>
 */

package akka.stream.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import scala.PartialFunction$;
import scala.runtime.AbstractPartialFunction;
import scala.runtime.BoxedUnit;

public class ActorSourceSinkCompileTest {

  interface Protocol {}
  class Init implements Protocol {}
  class Msg implements Protocol {}
  class Complete implements Protocol {}
  class Failure implements Protocol {
    public Exception ex;
  }

  {
    final ActorSystem<String> system = null;
    final ActorMaterializer mat = akka.stream.typed.ActorMaterializer.create(system);
  }

  {
    final ActorRef<String> ref = null;

    Source.<String>queue(10, OverflowStrategy.dropBuffer())
      .map(s -> s + "!")
      .to(ActorSink.actorRef(ref, "DONE", ex -> "FAILED: " + ex.getMessage()));
  }

  {
    final ActorRef<Protocol> ref = null;

    Source.<String>queue(10, OverflowStrategy.dropBuffer())
      .to(ActorSink.actorRefWithAck(
        ref,
        (sender, msg) -> new Init(),
        (sender) -> new Msg(),
        "ACK",
        new Complete(),
        (f) -> new Failure()));
  }

  {
    final AbstractPartialFunction<String, BoxedUnit> completionMatcher = new AbstractPartialFunction<String, BoxedUnit>() {
      @Override
      public boolean isDefinedAt(String s) {
        return s == "complete";
      }
    };

    ActorSource
      .actorRef(
        completionMatcher,
        PartialFunction$.MODULE$.empty(), // FIXME make the API nicer
        10,
        OverflowStrategy.dropBuffer())
      .to(Sink.seq());
  }

  {
    final AbstractPartialFunction<Protocol, Throwable> failureMatcher = new AbstractPartialFunction<Protocol, Throwable>() {
      @Override
      public boolean isDefinedAt(Protocol p) {
        return p instanceof Failure;
      }

      @Override
      public Throwable apply(Protocol p) {
        return ((Failure)p).ex;
      }
    };

    ActorSource
      .actorRef(
        PartialFunction$.MODULE$.empty(), // FIXME make the API nicer
        failureMatcher, 10,
        OverflowStrategy.dropBuffer())
      .to(Sink.seq());
  }

}
