/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package scala.concurrent.stm;

import static org.junit.Assert.*;
import org.junit.Test;

import scala.concurrent.stm.japi.Stm;
import static scala.concurrent.stm.japi.Stm.*;

import scala.runtime.AbstractFunction1;
import java.util.concurrent.Callable;

import java.util.Map;
import java.util.Set;
import java.util.List;

public class JavaAPITests {
    @Test
    public void createIntegerRef() {
        Ref.View<Integer> ref = newRef(0);
        int unboxed = ref.get();
        assertEquals(0, unboxed);
    }

    @Test
    public void atomicWithRunnable() {
        final Ref.View<Integer> ref = newRef(0);
        atomic(new Runnable() {
        	public void run() {
        		ref.set(10);
        	}
        });
        int value = ref.get();
        assertEquals(10, value);
    }

    @Test
    public void atomicWithCallable() {
        final Ref.View<Integer> ref = newRef(0);
        int oldValue = atomic(new Callable<Integer>() {
        	public Integer call() {
        		return ref.swap(10);
        	}
        });
        assertEquals(0, oldValue);
        int newValue = ref.get();
        assertEquals(10, newValue);
    }

    @Test(expected = TestException.class)
    public void failingTransaction() {
        final Ref.View<Integer> ref = newRef(0);
        try {
            atomic(new Runnable() {
            	public void run() {
        		    ref.set(10);
        		    throw new TestException();
        	    }
            });
        } catch (TestException e) {
            int value = ref.get();
            assertEquals(0, value);
            throw e;
        }
    }

    @Test
    public void transformInteger() {
        Ref.View<Integer> ref = newRef(0);
        transform(ref, new AbstractFunction1<Integer, Integer>() {
        	public Integer apply(Integer i) {
        		return i + 10;
        	}
        });
        int value = ref.get();
        assertEquals(10, value);
    }

    @Test
    public void incrementInteger() {
        Ref.View<Integer> ref = newRef(0);
        increment(ref, 10);
        int value = ref.get();
        assertEquals(10, value);
    }

    @Test
    public void incrementLong() {
        Ref.View<Long> ref = newRef(0L);
        increment(ref, 10L);
        long value = ref.get();
        assertEquals(10L, value);
    }

    @Test
    public void createAndUseTMap() {
        Map<Integer, String> map = newMap();
        map.put(1, "one");
        map.put(2, "two");
        assertEquals("one", map.get(1));
        assertEquals("two", map.get(2));
        assertTrue(map.containsKey(2));
        map.remove(2);
        assertFalse(map.containsKey(2));
    }

    @Test(expected = TestException.class)
    public void failingTMapTransaction() {
        final Map<Integer, String> map = newMap();
        try {
            atomic(new Runnable() {
            	public void run() {
        		    map.put(1, "one");
                    map.put(2, "two");
                    assertTrue(map.containsKey(1));
                    assertTrue(map.containsKey(2));
        		    throw new TestException();
        	    }
            });
        } catch (TestException e) {
            assertFalse(map.containsKey(1));
            assertFalse(map.containsKey(2));
            throw e;
        }
    }

    @Test
    public void createAndUseTSet() {
        Set<String> set = newSet();
        set.add("one");
        set.add("two");
        assertTrue(set.contains("one"));
        assertTrue(set.contains("two"));
        assertEquals(2, set.size());
        set.add("one");
        assertEquals(2, set.size());
        set.remove("two");
        assertFalse(set.contains("two"));
        assertEquals(1, set.size());
    }

    @Test
    public void createAndUseTArray() {
        List<String> list = newList(3);
        assertEquals(null, list.get(0));
        assertEquals(null, list.get(1));
        assertEquals(null, list.get(2));
        list.set(0, "zero");
        list.set(1, "one");
        list.set(2, "two");
        assertEquals("zero", list.get(0));
        assertEquals("one", list.get(1));
        assertEquals("two", list.get(2));
    }
}
