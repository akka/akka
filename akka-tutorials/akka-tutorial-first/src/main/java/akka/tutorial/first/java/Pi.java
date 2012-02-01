/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.tutorial.first.java;

//#imports
import akka.actor.Props;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.InternalActorRef;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.japi.Creator;
import akka.routing.*;
import scala.util.Timeout;

import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
//#imports

//#app
public class Pi {

    public static void main(String[] args) throws Exception {
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
                getSender().tell(new Result(result));
            } else {
                throw new IllegalArgumentException("Unknown message [" + message + "]");
            }
        }
    }
    //#worker

    //#master
    public static class Master extends UntypedActor {
        private final int nrOfMessages;
        private final int nrOfElements;
        private final CountDownLatch latch;

        private double pi;
        private int nrOfResults;
        private long start;

        private ActorRef router;

        public Master(final int nrOfWorkers, int nrOfMessages,
                      int nrOfElements, CountDownLatch latch) {
            this.nrOfMessages = nrOfMessages;
            this.nrOfElements = nrOfElements;
            this.latch = latch;

            //#create-router
            router = this.getContext().actorOf(
                new Props(Worker.class).withRouter(new RoundRobinRouter(nrOfWorkers)),
                "pi");
            //#create-router
        }

        //#master-receive
        public void onReceive(Object message) {
            //#handle-messages
            if (message instanceof Calculate) {
                for (int start = 0; start < nrOfMessages; start++) {
                    router.tell(new Work(start, nrOfElements), getSelf());
                }
            } else if (message instanceof Result) {
                Result result = (Result) message;
                pi += result.getValue();
                nrOfResults += 1;
                if (nrOfResults == nrOfMessages) getContext().stop(getSelf());
            } else throw new IllegalArgumentException("Unknown message [" + message + "]");
            //#handle-messages
        }
        //#master-receive

        @Override
        public void preStart() {
            start = System.currentTimeMillis();
        }

        @Override
        public void postStop() {
            System.out.println(String.format(
                "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis",
                pi, (System.currentTimeMillis() - start)));
            latch.countDown();
        }
    }
    //#master
    //#actors-and-messages

    public void calculate(final int nrOfWorkers,
                          final int nrOfElements,
                          final int nrOfMessages)
                          throws Exception {
        // Create an Akka system
        final ActorSystem system = ActorSystem.create();

        // this latch is only plumbing to know when the calculation is completed
        final CountDownLatch latch = new CountDownLatch(1);

        // create the master
        ActorRef master = system.actorOf(new Props(new UntypedActorFactory() {
            public UntypedActor create() {
                return new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch);
            }
        }));

        // start the calculation
        master.tell(new Calculate());

        // wait for master to shut down
        latch.await();

        // Shut down the system
        system.shutdown();
    }
}
//#app
