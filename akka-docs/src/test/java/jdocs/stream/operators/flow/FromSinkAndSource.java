/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.flow;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Tcp;
import akka.util.ByteString;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public class FromSinkAndSource {

  void halfClosedTcpServer() {
    // #halfClosedTcpServer
    ActorSystem system = null;

    // close in immediately
    Sink<ByteString, NotUsed> sink = Sink.cancelled();
    // periodic tick out
    Source<ByteString, Cancellable> source =
        Source.tick(Duration.ofSeconds(1), Duration.ofSeconds(1), "tick")
            .map(tick -> ByteString.fromString(System.currentTimeMillis() + "\n"));

    Flow<ByteString, ByteString, NotUsed> serverFlow = Flow.fromSinkAndSource(sink, source);

    Source<Tcp.IncomingConnection, CompletionStage<Tcp.ServerBinding>> connectionStream =
        Tcp.get(system).bind("127.0.0.1", 9999);

    connectionStream.runForeach(
        incomingConnection -> incomingConnection.handleWith(serverFlow, system), system);
    // #halfClosedTcpServer
  }
}
