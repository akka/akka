/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.tutorial.java.second;

import static akka.actor.Actors.actorOf;
import static akka.actor.Actors.poisonPill;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.asList;
import scala.Option;
import akka.actor.ActorRef;
import akka.actor.Channel;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.dispatch.Future;
import akka.japi.Procedure;
import akka.routing.CyclicIterator;
import akka.routing.InfiniteIterator;
import akka.routing.Routing.Broadcast;
import akka.routing.UntypedLoadBalancer;

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
          router.tell(new Work(arg, nrOfElements), getContext());
        }
        // Assume the gathering behavior
        become(gather(getContext().getChannel()));
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
            getContext().stop();
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
    ActorRef master = actorOf(new UntypedActorFactory() {
      public UntypedActor create() {
        return new Master(nrOfWorkers, nrOfMessages, nrOfElements);
      }
    }).start();

    // start the calculation
    long start = currentTimeMillis();

    // send calculate message
    long timeout = 60000;
    Future<Object> replyFuture = master.sendRequestReplyFuture(new Calculate(), timeout, null);
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
