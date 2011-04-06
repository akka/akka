/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.tutorial.java.second;

import static akka.actor.Actors.actorOf;
import static akka.actor.Actors.poisonPill;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.asList;

import java.util.concurrent.CountDownLatch;

import scala.Option;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.dispatch.Future;
import akka.event.EventHandler;
import akka.routing.CyclicIterator;
import akka.routing.InfiniteIterator;
import akka.routing.Routing.Broadcast;
import akka.routing.UntypedLoadBalancer;

/**
 * Second part in Akka tutorial for Java.
 * <p/>
 * Calculates Pi.
 * <p/>
 * Run on command line:
 * <pre>
 *   $ cd akka-1.1
 *   $ export AKKA_HOME=`pwd`
 *   $ javac -cp dist/akka-actor-1.1-SNAPSHOT.jar:scala-library.jar akka/tutorial/java/second/Pi.java
 *   $ java -cp dist/akka-actor-1.1-SNAPSHOT.jar:scala-library.jar:. akka.tutorial.java.second.Pi
 *   $ ...
 * </pre>
 * <p/>
 * Run it in Maven:
 * <pre>
 *   $ mvn
 *   > scala:console
 *   > val pi = new akka.tutorial.java.second.Pi
 *   > pi.calculate(4, 10000, 10000)
 *   > ...
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
public class Pi {

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
        getContext().replyUnsafe(new Result(calculatePiFor(work.getArg(), work.getNrOfElements()))); // perform the work
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

    static class PiRouter extends UntypedLoadBalancer {
      private final InfiniteIterator<ActorRef> workers;

      public PiRouter(ActorRef[] workers) {
        this.workers = new CyclicIterator<ActorRef>(asList(workers));
      }

      public InfiniteIterator<ActorRef> seq() {
        return workers;
      }
    }

    public Master(int nrOfWorkers, int nrOfMessages, int nrOfElements) {
      this.nrOfMessages = nrOfMessages;
      this.nrOfElements = nrOfElements;

      // create the workers
      final ActorRef[] workers = new ActorRef[nrOfWorkers];
      for (int i = 0; i < nrOfWorkers; i++) {
        workers[i] = actorOf(Worker.class).start();
      }

      // wrap them with a load-balancing router
      router = actorOf(new UntypedActorFactory() {
        public UntypedActor create() {
          return new PiRouter(workers);
        }
      }).start();
    }

    // message handler
    public void onReceive(Object message) {

      if (message instanceof Calculate) {
        // schedule work
        for (int arg = 0; arg < nrOfMessages; arg++) {
          router.sendOneWay(new Work(arg, nrOfElements), getContext());
        }

      } else if (message instanceof Result) {

        // handle result from the worker
        Result result = (Result) message;
        pi += result.getValue();
        nrOfResults += 1;
        if (nrOfResults == nrOfMessages) {
          System.out.println("# DONE");
          // send the pi result back to the guy who started the calculation
          // TODO wrong docs, channel() not there
//          getContext().channel()
          getContext().replyUnsafe(pi);
//          getContext().getSender().get().sendOneWay(pi, getContext());
//          getContext().getSenderFuture().get().completeWithResult(pi);
          // shut ourselves down, we're done
          getContext().stop();
        }

      } else throw new IllegalArgumentException("Unknown message [" + message + "]");
    }

    @Override
    public void postStop() {
      // send a PoisonPill to all workers telling them to shut down themselves
      router.sendOneWay(new Broadcast(poisonPill()));
      // send a PoisonPill to the router, telling him to shut himself down
      router.sendOneWay(poisonPill());
      
    }
  }

  // ==================
  // ===== Run it =====
  // ==================
  public void calculate(final int nrOfWorkers, final int nrOfElements, final int nrOfMessages) throws Exception {

    // create the master
    ActorRef master = actorOf(new UntypedActorFactory() {
      public UntypedActor create() {
        return new Master(nrOfWorkers, nrOfMessages, nrOfElements);
      }
    }).start();
    
    // start the calculation
    long start = currentTimeMillis();

    // send calculate message
    // TODO 60000
    long timeout = 10000;
    Future<Double> replyFuture = (Future<Double>) master.sendRequestReplyFuture(new Calculate(), timeout, null);
    Option<Double> result = replyFuture.await().resultOrException();
    if (result.isDefined()) {
      double pi = result.get();
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
