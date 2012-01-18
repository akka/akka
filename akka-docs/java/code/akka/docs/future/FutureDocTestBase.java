/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.future;

//#imports1
import akka.util.Timeout;
import akka.dispatch.Await;
import akka.dispatch.Future;

//#imports1

//#imports2
import akka.util.Duration;
import akka.japi.Function;
import java.util.concurrent.Callable;
import static akka.dispatch.Futures.future;
import static java.util.concurrent.TimeUnit.SECONDS;

//#imports2

//#imports3
import static akka.dispatch.Futures.sequence;

//#imports3

//#imports4
import static akka.dispatch.Futures.traverse;

//#imports4

//#imports5
import akka.japi.Function2;
import static akka.dispatch.Futures.fold;

//#imports5

//#imports6
import static akka.dispatch.Futures.reduce;

//#imports6

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import akka.testkit.AkkaSpec;
import akka.actor.Status.Failure;
import akka.actor.ActorSystem;
import akka.actor.UntypedActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.dispatch.Futures;
import akka.pattern.Patterns;

import static org.junit.Assert.*;

public class FutureDocTestBase {

  ActorSystem system;

  @Before
  public void setUp() {
    system = ActorSystem.create("MySystem", AkkaSpec.testConf());
  }

  @After
  public void tearDown() {
    system.shutdown();
  }

  @Test
  public void useBlockingFromActor() {
    ActorRef actor = system.actorOf(new Props(MyActor.class));
    String msg = "hello";
    //#ask-blocking
    Timeout timeout = system.settings().ActorTimeout();
    Future<Object> future = Patterns.ask(actor, msg, timeout);
    String result = (String) Await.result(future, timeout.duration());
    //#ask-blocking
    assertEquals("HELLO", result);
  }

  @Test
  public void useFutureEval() {
    //#future-eval
    Future<String> f = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, system.dispatcher());
    String result = (String) Await.result(f, Duration.create(1, SECONDS));
    //#future-eval
    assertEquals("HelloWorld", result);
  }

  @Test
  public void useMap() {
    //#map
    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, system.dispatcher());

    Future<Integer> f2 = f1.map(new Function<String, Integer>() {
      public Integer apply(String s) {
        return s.length();
      }
    });

    int result = Await.result(f2, Duration.create(1, SECONDS));
    assertEquals(10, result);
    //#map
  }

  @Test
  public void useMap2() throws Exception {
    //#map2
    Future<String> f1 = future(new Callable<String>() {
      public String call() throws Exception {
        Thread.sleep(100);
        return "Hello" + "World";
      }
    }, system.dispatcher());

    Future<Integer> f2 = f1.map(new Function<String, Integer>() {
      public Integer apply(String s) {
        return s.length();
      }
    });

    //#map2
    int result = Await.result(f2, Duration.create(1, SECONDS));
    assertEquals(10, result);
  }

  @Test
  public void useMap3() throws Exception {
    //#map3
    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, system.dispatcher());

    Thread.sleep(100);

    Future<Integer> f2 = f1.map(new Function<String, Integer>() {
      public Integer apply(String s) {
        return s.length();
      }
    });

    //#map3
    int result = Await.result(f2, Duration.create(1, SECONDS));
    assertEquals(10, result);
  }

  @Test
  public void useFlatMap() {
    //#flat-map
    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, system.dispatcher());

    Future<Integer> f2 = f1.flatMap(new Function<String, Future<Integer>>() {
      public Future<Integer> apply(final String s) {
        return future(new Callable<Integer>() {
          public Integer call() {
            return s.length();
          }
        }, system.dispatcher());
      }
    });

    //#flat-map
    int result = Await.result(f2, Duration.create(1, SECONDS));
    assertEquals(10, result);
  }

  @Test
  public void useSequence() {
    List<Future<Integer>> source = new ArrayList<Future<Integer>>();
    source.add(Futures.successful(1, system.dispatcher()));
    source.add(Futures.successful(2, system.dispatcher()));

    //#sequence
    //Some source generating a sequence of Future<Integer>:s
    Iterable<Future<Integer>> listOfFutureInts = source;

    // now we have a Future[Iterable[Integer]]
    Future<Iterable<Integer>> futureListOfInts = sequence(listOfFutureInts, system.dispatcher());

    // Find the sum of the odd numbers
    Future<Long> futureSum = futureListOfInts.map(new Function<Iterable<Integer>, Long>() {
      public Long apply(Iterable<Integer> ints) {
        long sum = 0;
        for (Integer i : ints)
          sum += i;
        return sum;
      }
    });

    long result = Await.result(futureSum, Duration.create(1, SECONDS));
    //#sequence
    assertEquals(3L, result);
  }

  @Test
  public void useTraverse() {
    //#traverse
    //Just a sequence of Strings
    Iterable<String> listStrings = Arrays.asList("a", "b", "c");

    Future<Iterable<String>> futureResult = traverse(listStrings, new Function<String, Future<String>>() {
      public Future<String> apply(final String r) {
        return future(new Callable<String>() {
          public String call() {
            return r.toUpperCase();
          }
        }, system.dispatcher());
      }
    }, system.dispatcher());

    //Returns the sequence of strings as upper case
    Iterable<String> result = Await.result(futureResult, Duration.create(1, SECONDS));
    assertEquals(Arrays.asList("A", "B", "C"), result);
    //#traverse
  }

  @Test
  public void useFold() {
    List<Future<String>> source = new ArrayList<Future<String>>();
    source.add(Futures.successful("a", system.dispatcher()));
    source.add(Futures.successful("b", system.dispatcher()));
    //#fold

    //A sequence of Futures, in this case Strings
    Iterable<Future<String>> futures = source;

    //Start value is the empty string
    Future<String> resultFuture = fold("", futures, new Function2<String, String, String>() {
      public String apply(String r, String t) {
        return r + t; //Just concatenate
      }
    }, system.dispatcher());
    String result = Await.result(resultFuture, Duration.create(1, SECONDS));
    //#fold

    assertEquals("ab", result);
  }

  @Test
  public void useReduce() {
    List<Future<String>> source = new ArrayList<Future<String>>();
    source.add(Futures.successful("a", system.dispatcher()));
    source.add(Futures.successful("b", system.dispatcher()));
    //#reduce

    //A sequence of Futures, in this case Strings
    Iterable<Future<String>> futures = source;

    Future<String> resultFuture = reduce(futures, new Function2<String, String, String>() {
      public String apply(String r, String t) {
        return r + t; //Just concatenate
      }
    }, system.dispatcher());

    String result = Await.result(resultFuture, Duration.create(1, SECONDS));
    //#reduce

    assertEquals("ab", result);
  }

  public static class MyActor extends UntypedActor {
    public void onReceive(Object message) {
      if (message instanceof String) {
        getSender().tell(((String) message).toUpperCase());
      } else if (message instanceof Integer) {
        int i = ((Integer) message).intValue();
        if (i < 0) {
          getSender().tell(new Failure(new ArithmeticException("Negative values not supported")));
        } else {
          getSender().tell(i);
        }
      } else {
        unhandled(message);
      }
    }
  }
}
