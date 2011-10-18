/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.tutorial.java.second;

import static akka.actor.Actors.poisonPill;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.asList;

import akka.AkkaApplication;
import akka.routing.RoutedProps;
import akka.routing.Routing;
import akka.routing.LocalConnectionManager;
import scala.Option;
import akka.actor.ActorRef;
import akka.actor.Actors;
import akka.actor.Channel;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.dispatch.Future;
import akka.japi.Procedure;
import akka.routing.Routing.Broadcast;
import scala.collection.JavaConversions;

import java.util.LinkedList;

public class Pi {

  private static final AkkaApplication app = new AkkaApplication();

  public static void main(String[] args) throws Exception {
    Pi pi = new Pi();
    pi.calculate(4, 10000, 10000);
  }

  // ====================
  // ===== Messages =====
  // ====================
  static class Calculate {}

  static class Work {
    private final int arg;
    private final int nrOfElements;

    public Work(int arg, int nrOfElements) {
      this.arg = arg;
      this.nrOfElements = nrOfElements;
    }

    public int getArg() { return arg; }
    public int getNrOfElements() { return nrOfElements; }
  }

  static class Result {
    private final double value;

    public Result(double value) {
      this.value = value;
    }

    public double getValue() { return value; }
  }

  // ==================
  // ===== Worker =====
  // ==================
  static class Worker extends UntypedActor {

    // define the work
    private double calculatePiFor(int arg, int nrOfElements) {
      double acc = 0.0;
      for (int i = arg * nrOfElements; i <= ((arg + 1) * nrOfElements - 1); i++) {
        acc += 4 * Math.pow(-1, i) / (2 * i + 1);
      }
      return acc;
    }

    // message handler
    public void onReceive(Object message) {
      if (message instanceof Work) {
        Work work = (Work) message;
        reply(new Result(calculatePiFor(work.getArg(), work.getNrOfElements()))); // perform the work
      } else throw new IllegalArgumentException("Unknown message [" + message + "]");
    }
  }

  // ==================
  // ===== Master =====
  // ==================
  static class Master extends UntypedActor {
    private final int nrOfMessages;
    private final int nrOfElements;

    private double pi;
    private int nrOfResults;

    private ActorRef router;

    public Master(int nrOfWorkers, int nrOfMessages, int nrOfElements) {
      this.nrOfMessages = nrOfMessages;
      this.nrOfElements = nrOfElements;

      LinkedList<ActorRef> workers = new LinkedList<ActorRef>();
      for (int i = 0; i < nrOfWorkers; i++) {
         ActorRef worker = app.actorOf(Worker.class);
         workers.add(worker);
      }

      router = app.actorOf(new RoutedProps().withRoundRobinRouter().withLocalConnections(workers), "pi");
    }

    @Override
    public void preStart() {
      become(scatter);
    }

    // message handler
    public void onReceive(Object message) {
      throw new IllegalStateException("Should be gather or scatter");
    }

    private final Procedure<Object> scatter = new Procedure<Object>() {
      public void apply(Object msg) {
        // schedule work
        for (int arg = 0; arg < nrOfMessages; arg++) {
          router.tell(new Work(arg, nrOfElements), getSelf());
        }
        // Assume the gathering behavior
        become(gather(getChannel()));
      }
    };

    private Procedure<Object> gather(final Channel<Object> recipient) {
      return new Procedure<Object>() {
        public void apply(Object msg) {
          // handle result from the worker
          Result result = (Result) msg;
          pi += result.getValue();
          nrOfResults += 1;
          if (nrOfResults == nrOfMessages) {
            // send the pi result back to the guy who started the calculation
            recipient.tell(pi);
            // shut ourselves down, we're done
            getSelf().stop();
          }
        }
      };
    }

    @Override
    public void postStop() {
      // send a PoisonPill to all workers telling them to shut down themselves
      router.tell(new Broadcast(poisonPill()));
      // send a PoisonPill to the router, telling him to shut himself down
      router.tell(poisonPill());
    }
  }

  // ==================
  // ===== Run it =====
  // ==================
  public void calculate(final int nrOfWorkers, final int nrOfElements, final int nrOfMessages) throws Exception {

    // create the master
    ActorRef master = app.actorOf(new UntypedActorFactory() {
      public UntypedActor create() {
        return new Master(nrOfWorkers, nrOfMessages, nrOfElements);
      }
    });

    // start the calculation
    long start = currentTimeMillis();

    // send calculate message
    long timeout = 60000;
    Future<Object> replyFuture = master.ask(new Calculate(), timeout, null);
    Option<Object> result = replyFuture.await().resultOrException();
    if (result.isDefined()) {
      double pi = (Double) result.get();
      // TODO java api for EventHandler?
//      EventHandler.info(this, String.format("\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis", pi, (currentTimeMillis() - start)));
      System.out.println(String.format("\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis", pi, (currentTimeMillis() - start)));
    } else {
      // TODO java api for EventHandler?
//      EventHandler.error(this, "Pi calculation did not complete within the timeout.");
      System.out.println("Pi calculation did not complete within the timeout.");
    }

  }
}
