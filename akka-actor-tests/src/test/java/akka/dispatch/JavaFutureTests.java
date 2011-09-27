package akka.dispatch;

import akka.actor.Timeout;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.Callable;
import java.util.LinkedList;
import java.lang.Iterable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import akka.japi.Function;
import akka.japi.Function2;
import akka.japi.Procedure;
import akka.japi.Option;
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

    @Test public void mustBeAbleToExecuteAnOnResultCallback() throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      Promise<String> cf = new akka.dispatch.DefaultPromise<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
      Future<String> f = cf;
      f.onResult(new Procedure<String>() {
          public void apply(String result) {
             if(result.equals("foo"))
               latch.countDown();
          }
      });

      cf.completeWithResult("foo");
      assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
      assertEquals(f.get(), "foo");
    }

    @Test public void mustBeAbleToExecuteAnOnExceptionCallback() throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      Promise<String> cf = new akka.dispatch.DefaultPromise<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
      Future<String> f = cf;
      f.onException(new Procedure<Throwable>() {
          public void apply(Throwable t) {
             if(t instanceof NullPointerException)
               latch.countDown();
          }
      });

      Throwable exception = new NullPointerException();
      cf.completeWithException(exception);
      assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
      assertEquals(f.exception().get(), exception);
    }

    @Test public void mustBeAbleToExecuteAnOnTimeoutCallback() throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      Promise<String> cf = new akka.dispatch.DefaultPromise<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
      Future<String> f = cf;
      f.onTimeout(new Procedure<Future<String>>() {
          public void apply(Future<String> future) {
             latch.countDown();
          }
      });

      assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
      assertTrue(f.value().isEmpty());
    }

    @Test public void mustBeAbleToExecuteAnOnCompleteCallback() throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      Promise<String> cf = new akka.dispatch.DefaultPromise<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
      Future<String> f = cf;
      f.onComplete(new Procedure<Future<String>>() {
          public void apply(akka.dispatch.Future<String> future) {
             latch.countDown();
          }
      });

      cf.completeWithResult("foo");
      assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
      assertEquals(f.get(), "foo");
    }

    @Test public void mustBeAbleToForeachAFuture() throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      Promise<String> cf = new akka.dispatch.DefaultPromise<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
      Future<String> f = cf;
      f.foreach(new Procedure<String>() {
          public void apply(String future) {
             latch.countDown();
          }
      });

      cf.completeWithResult("foo");
      assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
      assertEquals(f.get(), "foo");
    }

    @Test public void mustBeAbleToFlatMapAFuture() throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      Promise<String> cf = new akka.dispatch.DefaultPromise<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
      cf.completeWithResult("1000");
      Future<String> f = cf;
      Future<Integer> r = f.flatMap(new Function<String, Future<Integer>>() {
            public Future<Integer> apply(String r) {
                latch.countDown();
                Promise<Integer> cf = new akka.dispatch.DefaultPromise<Integer>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
                cf.completeWithResult(Integer.parseInt(r));
                return cf;
            }
        });

      assertEquals(f.get(), "1000");
      assertEquals(r.get().intValue(), 1000);
      assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
    }

    @Test public void mustBeAbleToFilterAFuture() throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      Promise<String> cf = new akka.dispatch.DefaultPromise<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
      Future<String> f = cf;
      Future<String> r = f.filter(new Function<String, Boolean>() {
          public Boolean apply(String r) {
              latch.countDown();
              return r.equals("foo");
          }
      });

      cf.completeWithResult("foo");
      assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
      assertEquals(f.get(), "foo");
      assertEquals(r.get(), "foo");
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

        Future<String> result = fold("", 15000,listFutures, new Function2<String,String,String>() {
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

        Future<String> result = reduce(listFutures, 15000, new Function2<String,String,String>() {
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

        Future<Iterable<String>> result = traverse(listStrings, new Function<String,Future<String>>() {
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

    @Test public void findForJavaApiMustWork() {
      LinkedList<Future<Integer>> listFutures = new LinkedList<Future<Integer>>();
      for (int i = 0; i < 10; i++) {
            final Integer fi = i;
            listFutures.add(future(new Callable<Integer>() {
                        public Integer call() {
                            return fi;
                        }
                    }));
        }
      final Integer expect = 5;
      Future<Option<Integer>> f = Futures.find(listFutures, new Function<Integer,Boolean>() {
        public Boolean apply(Integer i) {
            return i == 5;
        }
      }, Timeout.getDefault());

      final Integer got = f.get().get();
      assertEquals(expect, got);
    }
}
