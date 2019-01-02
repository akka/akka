/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.japi.JavaPartialFunction;
import akka.stream.ActorMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

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
    final ActorMaterializer mat = ActorMaterializerFactory.create(system);
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
    ActorSource
      .actorRef(
        (m) -> m == "complete",
        new JavaPartialFunction<String, Throwable>() {
          @Override
          public Throwable apply(String x, boolean isCheck) throws Exception {
            throw noMatch();
          }
        },
        10,
        OverflowStrategy.dropBuffer())
      .to(Sink.seq());
  }

  {
    final JavaPartialFunction<Protocol, Throwable> failureMatcher = new JavaPartialFunction<Protocol, Throwable>() {
      @Override
      public Throwable apply(Protocol p, boolean isCheck) throws Exception {
        if (p instanceof Failure) {
          return ((Failure)p).ex;
        }
        else {
          throw noMatch();
        }
      }
    };

    ActorSource
      .actorRef(
        (m) -> false,
        failureMatcher, 10,
        OverflowStrategy.dropBuffer())
      .to(Sink.seq());
  }

}
