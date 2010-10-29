package akka.japi;

import org.junit.Test;

import static org.junit.Assert.*;

public class JavaAPITestBase {

    @Test public void shouldCreateSomeString() {
        Option<String> o = Option.some("abc");
        assertFalse(o.isEmpty());
        assertTrue(o.isDefined());
        assertEquals("abc", o.get());
    }

    @Test public void shouldCreateNone() {
        Option<String> o1 = Option.none();
        assertTrue(o1.isEmpty());
        assertFalse(o1.isDefined());

        Option<Float> o2 = Option.none();
        assertTrue(o2.isEmpty());
        assertFalse(o2.isDefined());
    }

    @Test public void shouldEnterForLoop() {
        for(String s : Option.some("abc")) {
            return;
        }
        fail("for-loop not entered");
    }

    @Test public void shouldNotEnterForLoop() {
        for(Object o : Option.none()) {
            fail("for-loop entered");
        }
    }

    @Test public void shouldBeSingleton() {
        assertSame(Option.none(), Option.none());
    }
}
