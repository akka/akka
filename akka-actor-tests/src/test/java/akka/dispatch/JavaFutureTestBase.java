package akka.dispatch;

import akka.util.Timeout;
import akka.actor.ActorSystem;

import akka.japi.*;
import akka.util.Duration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.Callable;
import java.util.LinkedList;
import java.lang.Iterable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import akka.testkit.AkkaSpec;

public class JavaFutureTestBase {

  private static ActorSystem system;
  private static Timeout t;

  private final Duration timeout = Duration.create(5, TimeUnit.SECONDS);

  @BeforeClass
  public static void beforeAll() {
    system = ActorSystem.create("JavaFutureTests", AkkaSpec.testConf());
    t = system.settings().ActorTimeout();
  }

  @AfterClass
  public static void afterAll() {
    system.shutdown();
    system = null;
  }

  @Test
  public void mustBeAbleToMapAFuture() {

    Future<String> f1 = Futures.future(new Callable<String>() {
      public String call() {
        return "Hello";
      }
    }, system.dispatcher());

    Future<String> f2 = f1.map(new Function<String, String>() {
      public String apply(String s) {
        return s + " World";
      }
    });

    assertEquals("Hello World", Await.result(f2, timeout));
  }

  @Test
  public void mustBeAbleToExecuteAnOnResultCallback() throws Throwable {
    final CountDownLatch latch = new CountDownLatch(1);
    Promise<String> cf = Futures.promise(system.dispatcher());
    Future<String> f = cf;
    f.onSuccess(new Procedure<String>() {
        public void apply(String result) {
            if (result.equals("foo"))
                latch.countDown();
        }
    });

    cf.success("foo");
    assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
    assertEquals(Await.result(f, timeout), "foo");
  }

  @Test
  public void mustBeAbleToExecuteAnOnExceptionCallback() throws Throwable {
    final CountDownLatch latch = new CountDownLatch(1);
    Promise<String> cf = Futures.promise(system.dispatcher());
    Future<String> f = cf;
    f.onFailure(new Procedure<Throwable>() {
        public void apply(Throwable t) {
            if (t instanceof NullPointerException)
                latch.countDown();
        }
    });

    Throwable exception = new NullPointerException();
    cf.failure(exception);
    assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
    assertEquals(f.value().get().left().get(), exception);
  }

  @Test
  public void mustBeAbleToExecuteAnOnCompleteCallback() throws Throwable {
    final CountDownLatch latch = new CountDownLatch(1);
    Promise<String> cf = Futures.promise(system.dispatcher());
    Future<String> f = cf;
    f.onComplete(new Procedure2<Throwable,String>() {
      public void apply(Throwable t, String r) {
        latch.countDown();
      }
    });

    cf.success("foo");
    assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
    assertEquals(Await.result(f, timeout), "foo");
  }

  @Test
  public void mustBeAbleToForeachAFuture() throws Throwable {
    final CountDownLatch latch = new CountDownLatch(1);
    Promise<String> cf = Futures.promise(system.dispatcher());
    Future<String> f = cf;
    f.foreach(new Procedure<String>() {
      public void apply(String future) {
        latch.countDown();
      }
    });

    cf.success("foo");
    assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
    assertEquals(Await.result(f, timeout), "foo");
  }

  @Test
  public void mustBeAbleToFlatMapAFuture() throws Throwable {
    final CountDownLatch latch = new CountDownLatch(1);
    Promise<String> cf = Futures.promise(system.dispatcher());
    cf.success("1000");
    Future<String> f = cf;
    Future<Integer> r = f.flatMap(new Function<String, Future<Integer>>() {
      public Future<Integer> apply(String r) {
        latch.countDown();
        Promise<Integer> cf = Futures.promise(system.dispatcher());
        cf.success(Integer.parseInt(r));
        return cf;
      }
    });

    assertEquals(Await.result(f, timeout), "1000");
    assertEquals(Await.result(r, timeout).intValue(), 1000);
    assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
  }

  @Test
  public void mustBeAbleToFilterAFuture() throws Throwable {
    final CountDownLatch latch = new CountDownLatch(1);
    Promise<String> cf = Futures.promise(system.dispatcher());
    Future<String> f = cf;
    Future<String> r = f.filter(new Function<String, Boolean>() {
      public Boolean apply(String r) {
        latch.countDown();
        return r.equals("foo");
      }
    });

    cf.success("foo");
    assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
    assertEquals(Await.result(f, timeout), "foo");
    assertEquals(Await.result(r, timeout), "foo");
  }

  // TODO: Improve this test, perhaps with an Actor
  @Test
  public void mustSequenceAFutureList() {
    LinkedList<Future<String>> listFutures = new LinkedList<Future<String>>();
    LinkedList<String> listExpected = new LinkedList<String>();

    for (int i = 0; i < 10; i++) {
      listExpected.add("test");
      listFutures.add(Futures.future(new Callable<String>() {
        public String call() {
          return "test";
        }
      }, system.dispatcher()));
    }

    Future<Iterable<String>> futureList = Futures.sequence(listFutures, system.dispatcher());

    assertEquals(Await.result(futureList, timeout), listExpected);
  }

  // TODO: Improve this test, perhaps with an Actor
  @Test
  public void foldForJavaApiMustWork() {
    LinkedList<Future<String>> listFutures = new LinkedList<Future<String>>();
    StringBuilder expected = new StringBuilder();

    for (int i = 0; i < 10; i++) {
      expected.append("test");
      listFutures.add(Futures.future(new Callable<String>() {
        public String call() {
          return "test";
        }
      }, system.dispatcher()));
    }

    Future<String> result = Futures.fold("", listFutures, new Function2<String, String, String>() {
      public String apply(String r, String t) {
        return r + t;
      }
    }, system.dispatcher());

    assertEquals(Await.result(result, timeout), expected.toString());
  }

  @Test
  public void reduceForJavaApiMustWork() {
    LinkedList<Future<String>> listFutures = new LinkedList<Future<String>>();
    StringBuilder expected = new StringBuilder();

    for (int i = 0; i < 10; i++) {
      expected.append("test");
      listFutures.add(Futures.future(new Callable<String>() {
        public String call() {
          return "test";
        }
      }, system.dispatcher()));
    }

    Future<String> result = Futures.reduce(listFutures, new Function2<String, String, String>() {
      public String apply(String r, String t) {
        return r + t;
      }
    }, system.dispatcher());

    assertEquals(Await.result(result, timeout), expected.toString());
  }

  @Test
  public void traverseForJavaApiMustWork() {
    LinkedList<String> listStrings = new LinkedList<String>();
    LinkedList<String> expectedStrings = new LinkedList<String>();

    for (int i = 0; i < 10; i++) {
      expectedStrings.add("TEST");
      listStrings.add("test");
    }

    Future<Iterable<String>> result = Futures.traverse(listStrings, new Function<String, Future<String>>() {
      public Future<String> apply(final String r) {
        return Futures.future(new Callable<String>() {
          public String call() {
            return r.toUpperCase();
          }
        }, system.dispatcher());
      }
    }, system.dispatcher());

    assertEquals(Await.result(result, timeout), expectedStrings);
  }

  @Test
  public void findForJavaApiMustWork() {
    LinkedList<Future<Integer>> listFutures = new LinkedList<Future<Integer>>();
    for (int i = 0; i < 10; i++) {
      final Integer fi = i;
      listFutures.add(Futures.future(new Callable<Integer>() {
        public Integer call() {
          return fi;
        }
      }, system.dispatcher()));
    }
    final Option<Integer> expect = Option.some(5);
    Future<Option<Integer>> f = Futures.find(listFutures, new Function<Integer, Boolean>() {
      public Boolean apply(Integer i) {
        return i == 5;
      }
    }, system.dispatcher());

    assertEquals(expect, Await.result(f, timeout));
  }
  
  @Test
  public void recoverForJavaApiMustWork() {
    final Exception ex = new Exception("failure");
    final Future<Integer> f = Futures.future(new Callable<Integer>() {
      public Integer call() throws Exception {
        throw ex;
      }
    }, system.dispatcher());
    final Future<Integer> f1 = f.<Integer>recover(new Function<Throwable, Integer>() {
      public Integer apply(Throwable thr) {
        return 5;
      }
    });
    final Future<Integer> f2 = f.<Integer>recover(new Function<Throwable, Integer>() {
      public Integer apply(Throwable thr) throws Throwable {
        throw thr;
      }
    });
    assertEquals(Await.result(f1, timeout), (Integer) 5);
    Await.ready(f2, timeout);
    assertEquals(ex, f2.value().get().left().get());
  }

  @Test
  public void BlockMustBeCallable() {
    Promise<String> p = Futures.promise(system.dispatcher());
    Duration d = Duration.create(1, TimeUnit.SECONDS);
    p.success("foo");
    Await.ready(p, d);
    assertEquals(Await.result(p, d), "foo");
  }
}
