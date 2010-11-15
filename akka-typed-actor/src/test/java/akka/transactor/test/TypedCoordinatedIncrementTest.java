package akka.transactor.test;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import akka.actor.TypedActor;
import akka.transactor.Coordination;
import akka.transactor.Atomically;

import java.util.ArrayList;
import java.util.List;

public class TypedCoordinatedIncrementTest {
    List<TypedCounter> counters;
    TypedCounter failer;

    int numCounters = 5;

    @Before public void initialise() {
        counters = new ArrayList<TypedCounter>();
        for (int i = 1; i <= numCounters; i++) {
            TypedCounter counter = (TypedCounter) TypedActor.newInstance(TypedCounter.class, TypedCounterImpl.class);
            counters.add(counter);
        }
        failer = (TypedCounter) TypedActor.newInstance(TypedCounter.class, FailerImpl.class);
    }

    @Test public void incrementAllCountersWithSuccessfulTransaction() {
        Coordination.coordinate(true, new Atomically() {
            public void atomically() {
                for (TypedCounter counter : counters) {
                    counter.increment();
                }
            }
        });
        for (TypedCounter counter : counters) {
            int count = counter.get();
            assertEquals(1, count);
        }
    }

    @Test public void incrementNoCountersWithFailingTransaction() {
        try {
            Coordination.coordinate(true, new Atomically() {
                public void atomically() {
                    for (TypedCounter counter : counters) {
                        counter.increment();
                    }
                    failer.increment();
                }
            });
        } catch (Exception e) {
            // ignore
        }
        for (TypedCounter counter : counters) {
            int count = counter.get();
            assertEquals(0, count);
        }
    }

    @After public void stop() {
        for (TypedCounter counter : counters) {
            TypedActor.stop(counter);
        }
        TypedActor.stop(failer);
    }
}
