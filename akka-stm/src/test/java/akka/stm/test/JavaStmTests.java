package akka.stm.test;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.Before;

import akka.stm.*;

import org.multiverse.api.ThreadLocalTransaction;
import org.multiverse.api.TransactionConfiguration;
import org.multiverse.api.exceptions.ReadonlyException;

public class JavaStmTests {

    private Ref<Integer> ref;

    private int getRefValue() {
        return new Atomic<Integer>() {
            public Integer atomically() {
                return ref.get();
            }
        }.execute();
    }

    public int increment() {
        return new Atomic<Integer>() {
            public Integer atomically() {
                int inc = ref.get() + 1;
                ref.set(inc);
                return inc;
            }
        }.execute();
    }

    @Before public void initialise() {
        ref = new Ref<Integer>(0);
    }

    @Test public void incrementRef() {
        assertEquals(0, getRefValue());
        increment();
        increment();
        increment();
        assertEquals(3, getRefValue());
    }

    @Test public void failSetRef() {
        assertEquals(0, getRefValue());
        try {
            new Atomic() {
                public Object atomically() {
                    ref.set(3);
                    throw new RuntimeException();
                }
            }.execute();
        } catch(RuntimeException e) {}
        assertEquals(0, getRefValue());
    }

    @Test public void configureTransaction() {
        TransactionFactory txFactory = new TransactionFactoryBuilder()
            .setFamilyName("example")
            .setReadonly(true)
            .build();

        // get transaction config from multiverse
        TransactionConfiguration config = new Atomic<TransactionConfiguration>(txFactory) {
            public TransactionConfiguration atomically() {
                ref.get();
                return ThreadLocalTransaction.getThreadLocalTransaction().getConfiguration();
            }
        }.execute();

        assertEquals("example", config.getFamilyName());
        assertEquals(true, config.isReadonly());
    }

    @Test(expected=ReadonlyException.class) public void failReadonlyTransaction() {
        TransactionFactory txFactory = new TransactionFactoryBuilder()
            .setFamilyName("example")
            .setReadonly(true)
            .build();

        new Atomic(txFactory) {
            public Object atomically() {
                return ref.set(3);
            }
        }.execute();
    }
}
