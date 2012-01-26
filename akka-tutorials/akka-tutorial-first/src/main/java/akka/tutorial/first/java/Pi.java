/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.tutorial.first.java;

//#imports

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.routing.RoundRobinRouter;
import akka.util.Duration;
import java.util.concurrent.TimeUnit;

//#imports

//#app
public class Pi {

  public static void main(String[] args) {
    Pi pi = new Pi();
    pi.calculate(4, 10000, 10000);
  }

  //#actors-and-messages
  //#messages
  static class Calculate {
  }

  static class Work {
    private final int start;
    private final int nrOfElements;

    public Work(int start, int nrOfElements) {
      this.start = start;
      this.nrOfElements = nrOfElements;
    }

    public int getStart() {
      return start;
    }

    public int getNrOfElements() {
      return nrOfElements;
    }
  }

  static class Result {
    private final double value;

    public Result(double value) {
      this.value = value;
    }

    public double getValue() {
      return value;
    }
  }

  static class PiApproximation {
    private final double pi;
    private final Duration duration;

    public PiApproximation(double pi, Duration duration) {
      this.pi = pi;
      this.duration = duration;
    }

    public double getPi() {
      return pi;
    }

    public Duration getDuration() {
      return duration;
    }
  }

  //#messages

  //#worker
  public static class Worker extends UntypedActor {

    //#calculatePiFor
    private double calculatePiFor(int start, int nrOfElements) {
      double acc = 0.0;
      for (int i = start * nrOfElements; i <= ((start + 1) * nrOfElements - 1); i++) {
        acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1);
      }
      return acc;
    }

    //#calculatePiFor

    public void onReceive(Object message) {
      if (message instanceof Work) {
        Work work = (Work) message;
        double result = calculatePiFor(work.getStart(), work.getNrOfElements());
        getSender().tell(new Result(result), getSelf());
      } else {
        unhandled(message);
      }
    }
  }

  //#worker

  //#master
  public static class Master extends UntypedActor {
    private final int nrOfMessages;
    private final int nrOfElements;

    private double pi;
    private int nrOfResults;
    private final long start = System.currentTimeMillis();

    private final ActorRef listener;
    private final ActorRef workerRouter;

    public Master(final int nrOfWorkers, int nrOfMessages, int nrOfElements, ActorRef listener) {
      this.nrOfMessages = nrOfMessages;
      this.nrOfElements = nrOfElements;
      this.listener = listener;

      //#create-router
      workerRouter = this.getContext().actorOf(new Props(Worker.class).withRouter(new RoundRobinRouter(nrOfWorkers)),
          "workerRouter");
      //#create-router
    }

    //#master-receive
    public void onReceive(Object message) {
      //#handle-messages
      if (message instanceof Calculate) {
        for (int start = 0; start < nrOfMessages; start++) {
          workerRouter.tell(new Work(start, nrOfElements), getSelf());
        }
      } else if (message instanceof Result) {
        Result result = (Result) message;
        pi += result.getValue();
        nrOfResults += 1;
        if (nrOfResults == nrOfMessages) {
          // Send the result to the listener
          Duration duration = Duration.create(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
          listener.tell(new PiApproximation(pi, duration), getSelf());
          // Stops this actor and all its supervised children
          getContext().stop(getSelf());
        }
      } else {
        unhandled(message);
      }
      //#handle-messages
    }
    //#master-receive
  }

  //#master

  //#result-listener
  public static class Listener extends UntypedActor {
    public void onReceive(Object message) {
      if (message instanceof PiApproximation) {
        PiApproximation approximation = (PiApproximation) message;
        System.out.println(String.format("\n\tPi approximation: \t\t%s\n\tCalculation time: \t%s",
            approximation.getPi(), approximation.getDuration()));
        getContext().system().shutdown();
      } else {
        unhandled(message);
      }
    }
  }

  //#result-listener
  //#actors-and-messages

  public void calculate(final int nrOfWorkers, final int nrOfElements, final int nrOfMessages) {
    // Create an Akka system
    ActorSystem system = ActorSystem.create("PiSystem");

    // create the result listener, which will print the result and shutdown the system
    final ActorRef listener = system.actorOf(new Props(Listener.class));

    // create the master
    ActorRef master = system.actorOf(new Props(new UntypedActorFactory() {
      public UntypedActor create() {
        return new Master(nrOfWorkers, nrOfMessages, nrOfElements, listener);
      }
    }), "master");

    // start the calculation
    master.tell(new Calculate());

  }
}
//#app
