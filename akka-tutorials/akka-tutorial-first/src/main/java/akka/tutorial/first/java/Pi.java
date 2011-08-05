/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.tutorial.first.java;

import static akka.actor.Actors.actorOf;
import static akka.actor.Actors.poisonPill;
import static java.util.Arrays.asList;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.routing.CyclicIterator;
import akka.routing.InfiniteIterator;
import akka.routing.Routing.Broadcast;
import akka.routing.UntypedLoadBalancer;

import java.util.concurrent.CountDownLatch;

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
    private final int start;
    private final int nrOfElements;

    public Work(int start, int nrOfElements) {
      this.start = start;
      this.nrOfElements = nrOfElements;
    }

    public int getStart() { return start; }
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
    private double calculatePiFor(int start, int nrOfElements) {
      double acc = 0.0;
      for (int i = start * nrOfElements; i <= ((start + 1) * nrOfElements - 1); i++) {
        acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1);
      }
      return acc;
    }

    // message handler
    public void onReceive(Object message) {
      if (message instanceof Work) {
        Work work = (Work) message;

        // perform the work
        double result = calculatePiFor(work.getStart(), work.getNrOfElements());

        // reply with the result
        getContext().replyUnsafe(new Result(result));

      } else throw new IllegalArgumentException("Unknown message [" + message + "]");
    }
  }

  // ==================
  // ===== Master =====
  // ==================
  static class Master extends UntypedActor {
    private final int nrOfMessages;
    private final int nrOfElements;
    private final CountDownLatch latch;

    private double pi;
    private int nrOfResults;
    private long start;

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

    public Master(int nrOfWorkers, int nrOfMessages, int nrOfElements, CountDownLatch latch) {
      this.nrOfMessages = nrOfMessages;
      this.nrOfElements = nrOfElements;
      this.latch = latch;

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
        for (int start = 0; start < nrOfMessages; start++) {
          router.tell(new Work(start, nrOfElements), getContext());
        }

        // send a PoisonPill to all workers telling them to shut down themselves
        router.tell(new Broadcast(poisonPill()));

        // send a PoisonPill to the router, telling him to shut himself down
        router.tell(poisonPill());

      } else if (message instanceof Result) {

        // handle result from the worker
        Result result = (Result) message;
        pi += result.getValue();
        nrOfResults += 1;
        if (nrOfResults == nrOfMessages) getContext().stop();

      } else throw new IllegalArgumentException("Unknown message [" + message + "]");
    }

    @Override
    public void preStart() {
      start = System.currentTimeMillis();
    }

    @Override
    public void postStop() {
      // tell the world that the calculation is complete
      System.out.println(String.format(
        "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis",
        pi, (System.currentTimeMillis() - start)));
      latch.countDown();
    }
  }

  // ==================
  // ===== Run it =====
  // ==================
  public void calculate(final int nrOfWorkers, final int nrOfElements, final int nrOfMessages)
    throws Exception {

    // this latch is only plumbing to know when the calculation is completed
    final CountDownLatch latch = new CountDownLatch(1);

    // create the master
    ActorRef master = actorOf(new UntypedActorFactory() {
      public UntypedActor create() {
        return new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch);
      }
    }).start();

    // start the calculation
    master.tell(new Calculate());

    // wait for master to shut down
    latch.await();
  }
}
