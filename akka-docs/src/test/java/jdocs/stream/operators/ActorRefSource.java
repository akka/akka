/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

//#sourceActorRefBufferSize
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.stream.*;
import static akka.pattern.PatternsCS.ask;

import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

//#sourceActorRefBufferSize

class ActorRefSource {

  void example() {
    //#sourceActorRefBufferSize
    ActorSystem system = ActorSystem.create("ExampleSystem");
    ActorMaterializer mat = ActorMaterializer.create(system);
    Timeout t = Timeout.create(Duration.ofSeconds(5));

    ActorRef ref = Source.actorRef(1000, OverflowStrategy.fail()).to(Sink.ignore()).run(mat);
    CompletableFuture<Object> future1 = ask(ref, GetBufferStatus.getInstance(), t).toCompletableFuture();
    future1.thenRun(() -> {
      BufferStatus status = (BufferStatus) future1.join();
      System.out.println("Buffer status: " + status.getUsed() + "/" + status.getCapacity());
    });
    //#sourceActorRefBufferSize
  }
}
