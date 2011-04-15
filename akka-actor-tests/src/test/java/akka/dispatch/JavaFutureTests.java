package akka.dispatch;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.Callable;
import java.util.LinkedList;
import akka.japi.Function;
import akka.japi.Procedure;
import scala.Some;
import scala.Right;
import static akka.dispatch.Futures.future;
import static akka.dispatch.Futures.traverse;
import static akka.dispatch.Futures.sequence;

public class JavaFutureTests {

    @Test public void mustBeAbleToMapAFuture() {
        Future<String> f1 = future(new Callable<String>() {
                public String call() {
                    return "Hello";
                }
            });

        Future<String> f2 = f1.map(new Function<String, String>() {
                public String apply(String s) {
                    return s + " World";
                }
            });

        assertEquals("Hello World", f2.get());
    }

    // TODO: Improve this test, perhaps with an Actor
    @Test public void mustSequenceAFutureList() {
        LinkedList<Future<String>> listFutures = new LinkedList<Future<String>>();
        LinkedList<String> listExpected = new LinkedList<String>();

        for (int i = 0; i < 10; i++) {
            listExpected.add("test");
            listFutures.add(future(new Callable<String>() {
                        public String call() {
                            return "test";
                        }
                    }));
        }

        Future<LinkedList<String>> futureList = sequence(listFutures);

        assertEquals(futureList.get(), listExpected);
    }

}
