package akka.dispatch;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.Callable;
import java.util.LinkedList;
import java.lang.Iterable;
import akka.japi.Function;
import akka.japi.Function2;
import akka.japi.Procedure;
import scala.Some;
import scala.Right;
import static akka.dispatch.Futures.*;

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

        Future<Iterable<String>> futureList = sequence(listFutures);

        assertEquals(futureList.get(), listExpected);
    }

    // TODO: Improve this test, perhaps with an Actor
    @Test public void foldForJavaApiMustWork() {
        LinkedList<Future<String>> listFutures = new LinkedList<Future<String>>();
        StringBuilder expected = new StringBuilder();

        for (int i = 0; i < 10; i++) {
            expected.append("test");
            listFutures.add(future(new Callable<String>() {
                        public String call() {
                            return "test";
                        }
                    }));
        }

        Future<String> result = fold("", 15000,listFutures, new Function2<String,String,String>(){
          public String apply(String r, String t) {
              return r + t;
          }
        });

        assertEquals(result.get(), expected.toString());
    }

    @Test public void reduceForJavaApiMustWork() {
        LinkedList<Future<String>> listFutures = new LinkedList<Future<String>>();
        StringBuilder expected = new StringBuilder();

        for (int i = 0; i < 10; i++) {
            expected.append("test");
            listFutures.add(future(new Callable<String>() {
                        public String call() {
                            return "test";
                        }
                    }));
        }

        Future<String> result = reduce(listFutures, 15000, new Function2<String,String,String>(){
          public String apply(String r, String t) {
              return r + t;
          }
        });

        assertEquals(result.get(), expected.toString());
    }

    @Test public void traverseForJavaApiMustWork() {
        LinkedList<String> listStrings = new LinkedList<String>();
        LinkedList<String> expectedStrings = new LinkedList<String>();

        for (int i = 0; i < 10; i++) {
            expectedStrings.add("TEST");
            listStrings.add("test");
        }

        Future<Iterable<String>> result = traverse(listStrings, new Function<String,Future<String>>(){
          public Future<String> apply(final String r) {
              return future(new Callable<String>() {
                        public String call() {
                            return r.toUpperCase();
                        }
                    });
          }
        });

        assertEquals(result.get(), expectedStrings);
    }
}
