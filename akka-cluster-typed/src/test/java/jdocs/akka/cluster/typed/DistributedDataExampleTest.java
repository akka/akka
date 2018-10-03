/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package jdocs.akka.cluster.typed;

// #sample


// #sample

import akka.actor.typed.ActorRef;
import akka.cluster.ddata.GCounter;
import akka.cluster.ddata.GCounterKey;
import akka.cluster.ddata.Key;
import akka.cluster.ddata.ReplicatedData;
import akka.cluster.ddata.typed.javadsl.Replicator;

public class DistributedDataExampleTest {

  static // #sample
  class Counter {
   /*

  private sealed trait InternalMsg extends ClientCommand
  private case class InternalUpdateResponse[A <: ReplicatedData](rsp: Replicator.UpdateResponse[A]) extends InternalMsg
  private case class InternalGetResponse[A <: ReplicatedData](rsp: Replicator.GetResponse[A]) extends InternalMsg

  val Key = GCounterKey("counter")
    */

    interface ClientCommand {}
    static final ClientCommand INCREMENT = new ClientCommand() { };
    static final class GetValue implements ClientCommand {
      final ActorRef<Integer> replyTo;
      public GetValue(ActorRef<Integer> replyTo) {
        this.replyTo = replyTo;
      }
    }

    private interface InternalMsg extends ClientCommand {}
    private static final class InternalupdateResponse<A extends ReplicatedData> {
      final Replicator.UpdateResponse<A> rsp;
      public InternalupdateResponse(Replicator.UpdateResponse<A> rsp) {
        this.rsp = rsp;
      }
    }
    private static final class InternalGetResponse<A extends ReplicatedData> {
      final Replicator.GetResponse<A> rsp;
      public InternalGetResponse(Replicator.GetResponse<A> rsp) {
        this.rsp = rsp;
      }
    }

    private static final Key<GCounter> KEY = GCounterKey.create("counter");

  }
  // #sample
}
