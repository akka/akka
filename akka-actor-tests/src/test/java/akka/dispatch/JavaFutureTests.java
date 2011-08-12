package akka.dispatch;

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

    /**
     * private[japi] final def onTimeout[A >: T](proc: Procedure[japi.Future[A]]): this.type = self onTimeout proc
    private[japi] final def onResult[A >: T](proc: Procedure[A]): this.type = self.onResult({ case r: A => proc(r) }: PartialFunction[T, Unit])
    private[japi] final def onException(proc: Procedure[Throwable]): this.type = self.onException({ case t: Throwable => proc(t) }:PartialFunction[Throwable,Unit])
    private[japi] final def onComplete[A >: T](proc: Procedure[japi.Future[A]]): this.type = self.onComplete(proc(_))
    private[japi] final def map[A >: T, B](f: JFunc[A, B]): akka.dispatch.Future[B] = self.map(f(_))
    private[japi] final def flatMap[A >: T, B](f: JFunc[A, akka.dispatch.Future[B]]): akka.dispatch.Future[B] = self.flatMap(f(_))
    private[japi] final def foreach[A >: T](proc: Procedure[A]): Unit = self.foreach(proc(_))
    private[japi] final def filter(p: JFunc[Any, Boolean]): akka.dispatch.Future[Any] = self.filter(p(_))
     */

    @Test public void mustBeAbleToExecuteAnOnResultCallback() throws Throwable {
      final CountDownLatch latch = new CountDownLatch(1);
      CompletableFuture<String> cf = new akka.dispatch.DefaultCompletableFuture<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
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
      CompletableFuture<String> cf = new akka.dispatch.DefaultCompletableFuture<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
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
      CompletableFuture<String> cf = new akka.dispatch.DefaultCompletableFuture<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
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
      CompletableFuture<String> cf = new akka.dispatch.DefaultCompletableFuture<String>(1000, TimeUnit.MILLISECONDS, Dispatchers.defaultGlobalDispatcher());
      Future<String> f = cf;
      f.onComplete(new Procedure<Future<String>>() {
          public void apply(akka.dispatch.Future<String> future) {
             if(future.result().isDefined() && future.result().get().equals("foo"))
             latch.countDown();
          }
      });

      cf.completeWithResult("foo");
      assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
      assertEquals(f.get(), "foo");
    }

    @Test public void mustBeAbleToForeachAFuture() {

    }

    @Test public void mustBeAbleToFlatMapAFuture() {

    }

    @Test public void mustBeAbleToFilterAFuture() {

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
