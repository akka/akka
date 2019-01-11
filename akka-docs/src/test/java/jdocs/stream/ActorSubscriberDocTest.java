/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.routing.ActorRefRoutee;
import akka.routing.RoundRobinRoutingLogic;
import akka.routing.Routee;
import akka.routing.Router;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.actor.AbstractActorSubscriber;
import akka.stream.actor.ActorSubscriberMessage;
import akka.stream.actor.MaxInFlightRequestStrategy;
import akka.stream.actor.RequestStrategy;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import jdocs.AbstractJavaTest;
import akka.testkit.javadsl.TestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;
import java.time.Duration;

import static org.junit.Assert.assertEquals;

public class ActorSubscriberDocTest extends AbstractJavaTest {

  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("ActorSubscriberDocTest");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  // #worker-pool
  public static class WorkerPoolProtocol {

    public static class Msg {
      public final int id;
      public final ActorRef replyTo;

      public Msg(int id, ActorRef replyTo) {
        this.id = id;
        this.replyTo = replyTo;
      }

      @Override
      public String toString() {
        return String.format("Msg(%s, %s)", id, replyTo);
      }
    }

    public static Msg msg(int id, ActorRef replyTo) {
      return new Msg(id, replyTo);
    }

    public static class Work {
      public final int id;

      public Work(int id) {
        this.id = id;
      }

      @Override
      public String toString() {
        return String.format("Work(%s)", id);
      }
    }

    public static Work work(int id) {
      return new Work(id);
    }

    public static class Reply {
      public final int id;

      public Reply(int id) {
        this.id = id;
      }

      @Override
      public String toString() {
        return String.format("Reply(%s)", id);
      }
    }

    public static Reply reply(int id) {
      return new Reply(id);
    }

    public static class Done {
      public final int id;

      public Done(int id) {
        this.id = id;
      }

      @Override
      public String toString() {
        return String.format("Done(%s)", id);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }

        Done done = (Done) o;

        if (id != done.id) {
          return false;
        }

        return true;
      }

      @Override
      public int hashCode() {
        return id;
      }
    }

    public static Done done(int id) {
      return new Done(id);
    }
  }

  public static class WorkerPool extends AbstractActorSubscriber {

    public static Props props() {
      return Props.create(WorkerPool.class);
    }

    final int MAX_QUEUE_SIZE = 10;
    final Map<Integer, ActorRef> queue = new HashMap<>();

    final Router router;

    @Override
    public RequestStrategy requestStrategy() {
      return new MaxInFlightRequestStrategy(MAX_QUEUE_SIZE) {
        @Override
        public int inFlightInternally() {
          return queue.size();
        }
      };
    }

    public WorkerPool() {
      final List<Routee> routees = new ArrayList<>();
      for (int i = 0; i < 3; i++)
        routees.add(new ActorRefRoutee(getContext().actorOf(Props.create(Worker.class))));
      router = new Router(new RoundRobinRoutingLogic(), routees);
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(
              ActorSubscriberMessage.OnNext.class,
              on -> on.element() instanceof WorkerPoolProtocol.Msg,
              onNext -> {
                WorkerPoolProtocol.Msg msg = (WorkerPoolProtocol.Msg) onNext.element();
                queue.put(msg.id, msg.replyTo);

                if (queue.size() > MAX_QUEUE_SIZE)
                  throw new RuntimeException("queued too many: " + queue.size());

                router.route(WorkerPoolProtocol.work(msg.id), getSelf());
              })
          .match(
              ActorSubscriberMessage.onCompleteInstance().getClass(),
              complete -> {
                if (queue.isEmpty()) {
                  getContext().stop(getSelf());
                }
              })
          .match(
              WorkerPoolProtocol.Reply.class,
              reply -> {
                int id = reply.id;
                queue.get(id).tell(WorkerPoolProtocol.done(id), getSelf());
                queue.remove(id);
                if (canceled() && queue.isEmpty()) {
                  getContext().stop(getSelf());
                }
              })
          .build();
    }
  }

  static class Worker extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(
              WorkerPoolProtocol.Work.class,
              work -> {
                // ...
                getSender().tell(WorkerPoolProtocol.reply(work.id), getSelf());
              })
          .build();
    }
  }
  // #worker-pool

  @Test
  public void demonstrateActorPublisherUsage() {
    new TestKit(system) {

      {
        final ActorRef replyTo = getTestActor();

        // #actor-subscriber-usage
        final int N = 117;
        final List<Integer> data = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
          data.add(i);
        }

        final ActorRef worker =
            Source.from(data)
                .map(i -> WorkerPoolProtocol.msg(i, replyTo))
                .runWith(Sink.<WorkerPoolProtocol.Msg>actorSubscriber(WorkerPool.props()), mat);
        // #actor-subscriber-usage

        watch(worker);

        List<Object> got = new ArrayList<>(receiveN(N));
        Collections.sort(
            got,
            new Comparator<Object>() {
              @Override
              public int compare(Object o1, Object o2) {
                if (o1 instanceof WorkerPoolProtocol.Done
                    && o2 instanceof WorkerPoolProtocol.Done) {
                  return ((WorkerPoolProtocol.Done) o1).id - ((WorkerPoolProtocol.Done) o2).id;
                } else return 0;
              }
            });
        int i = 0;
        for (; i < N; i++) {
          assertEquals(
              String.format("Expected %d, but got %s", i, got.get(i)),
              WorkerPoolProtocol.done(i),
              got.get(i));
        }
        assertEquals(String.format("Expected 117 messages but got %d", i), i, 117);
        expectTerminated(Duration.ofSeconds(10), worker);
      }
    };
  }
}
