/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.future;

//#imports1
import akka.dispatch.*;
import scala.concurrent.Await;
import akka.util.Timeout;

//#imports1

//#imports2
import scala.concurrent.util.Duration;
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

//#imports7
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContext$;

//#imports7

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import akka.testkit.AkkaSpec;
import akka.actor.Status.Failure;
import akka.actor.ActorSystem;
import akka.actor.UntypedActor;
import akka.actor.ActorRef;
import akka.actor.Props;
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

  @SuppressWarnings("unchecked") @Test public void useCustomExecutionContext() throws Exception {
      ExecutorService yourExecutorServiceGoesHere = Executors.newSingleThreadExecutor();
      //#diy-execution-context
      ExecutionContext ec =
        ExecutionContext$.MODULE$.fromExecutorService(yourExecutorServiceGoesHere, (scala.Function1<java.lang.Throwable,scala.runtime.BoxedUnit>)(ExecutionContext$.MODULE$.fromExecutorService$default$2()));

      //Use ec with your Futures
      Future<String> f1 = Futures.successful("foo", ec);

      // Then you shut the ExecutorService down somewhere at the end of your program/application.
      yourExecutorServiceGoesHere.shutdown();
      //#diy-execution-context
  }

  @Test
  public void useBlockingFromActor() throws Exception {
    ActorRef actor = system.actorOf(new Props(MyActor.class));
    String msg = "hello";
    //#ask-blocking
    Timeout timeout = new Timeout(Duration.parse("5 seconds"));
    Future<Object> future = Patterns.ask(actor, msg, timeout);
    String result = (String) Await.result(future, timeout.duration());
    //#ask-blocking
    assertEquals("HELLO", result);
  }

  @Test
  public void useFutureEval() throws Exception {
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
  public void useMap() throws Exception {
    //#map
    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, system.dispatcher());

    Future<Integer> f2 = f1.map(new Mapper<String, Integer>() {
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

    Future<Integer> f2 = f1.map(new Mapper<String, Integer>() {
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

    Future<Integer> f2 = f1.map(new Mapper<String, Integer>() {
      public Integer apply(String s) {
        return s.length();
      }
    });

    //#map3
    int result = Await.result(f2, Duration.create(1, SECONDS));
    assertEquals(10, result);
  }

  @Test
  public void useFlatMap() throws Exception {
    //#flat-map
    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, system.dispatcher());

    Future<Integer> f2 = f1.flatMap(new Mapper<String, Future<Integer>>() {
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
  public void useSequence() throws Exception {
    List<Future<Integer>> source = new ArrayList<Future<Integer>>();
    source.add(Futures.successful(1, system.dispatcher()));
    source.add(Futures.successful(2, system.dispatcher()));

    //#sequence
    //Some source generating a sequence of Future<Integer>:s
    Iterable<Future<Integer>> listOfFutureInts = source;

    // now we have a Future[Iterable[Integer]]
    Future<Iterable<Integer>> futureListOfInts = sequence(listOfFutureInts, system.dispatcher());

    // Find the sum of the odd numbers
    Future<Long> futureSum = futureListOfInts.map(new Mapper<Iterable<Integer>, Long>() {
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
  public void useTraverse() throws Exception {
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
  public void useFold() throws Exception {
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
  public void useReduce() throws Exception {
    List<Future<String>> source = new ArrayList<Future<String>>();
    source.add(Futures.successful("a", system.dispatcher()));
    source.add(Futures.successful("b", system.dispatcher()));
    //#reduce

    //A sequence of Futures, in this case Strings
    Iterable<Future<String>> futures = source;

    Future<Object> resultFuture = reduce(futures, new Function2<Object, String, Object>() {
      public Object apply(Object r, String t) {
        return r + t; //Just concatenate
      }
    }, system.dispatcher());

    Object result = Await.result(resultFuture, Duration.create(1, SECONDS));
    //#reduce

    assertEquals("ab", result);
  }

  @Test
  public void useSuccessfulAndFailed() throws Exception {
    //#successful
    Future<String> future = Futures.successful("Yay!", system.dispatcher());
    //#successful
    //#failed
    Future<String> otherFuture = Futures.failed(new IllegalArgumentException("Bang!"), system.dispatcher());
    //#failed
    Object result = Await.result(future, Duration.create(1, SECONDS));
    assertEquals("Yay!", result);
    Throwable result2 = Await.result(otherFuture.failed(), Duration.create(1, SECONDS));
    assertEquals("Bang!", result2.getMessage());
  }

  @Test
  public void useFilter() throws Exception {
    //#filter
    Future<Integer> future1 = Futures.successful(4, system.dispatcher());
    Future<Integer> successfulFilter = future1.filter(Filter.filterOf(new Function<Integer, Boolean>() {
      public Boolean apply(Integer i) {
        return i % 2 == 0;
      }
    }));

    Future<Integer> failedFilter = future1.filter(Filter.filterOf(new Function<Integer, Boolean>() {
      public Boolean apply(Integer i) {
        return i % 2 != 0;
      }
    }));
    //When filter fails, the returned Future will be failed with a scala.MatchError
    //#filter
  }

  public void sendToTheInternetz(String s) {

  }

  public void sendToIssueTracker(Throwable t) {

  }

  @Test
  public void useAndThen() {
    //#and-then
    Future<String> future1 = Futures.successful("value", system.dispatcher()).andThen(new OnComplete<String>() {
        public void onComplete(Throwable failure, String result) {
            if (failure != null)
                sendToIssueTracker(failure);
        }
    }).andThen(new OnComplete<String>() {
      public void onComplete(Throwable failure, String result) {
        if (result != null)
          sendToTheInternetz(result);
      }
    });
    //#and-then
  }

  @Test
  public void useRecover() throws Exception {
    //#recover
    Future<Integer> future = future(new Callable<Integer>() {
      public Integer call() {
        return 1 / 0;
      }
    }, system.dispatcher()).recover(new Recover<Integer>() {
      public Integer recover(Throwable problem) throws Throwable {
        if (problem instanceof ArithmeticException)
          return 0;
        else
          throw problem;
      }
    });
    int result = Await.result(future, Duration.create(1, SECONDS));
    assertEquals(result, 0);
    //#recover
  }

  @Test
  public void useTryRecover() throws Exception {
    //#try-recover
    Future<Integer> future = future(new Callable<Integer>() {
      public Integer call() {
        return 1 / 0;
      }
    }, system.dispatcher()).recoverWith(new Recover<Future<Integer>>() {
      public Future<Integer> recover(Throwable problem) throws Throwable {
        if (problem instanceof ArithmeticException) {
          return future(new Callable<Integer>() {
            public Integer call() {
              return 0;
            }
          }, system.dispatcher());
        } else
          throw problem;
      }
    });
    int result = Await.result(future, Duration.create(1, SECONDS));
    assertEquals(result, 0);
    //#try-recover
  }

  @Test
  public void useOnSuccessOnFailureAndOnComplete() throws Exception {
    {
      Future<String> future = Futures.successful("foo", system.dispatcher());

      //#onSuccess
      future.onSuccess(new OnSuccess<String>() {
        public void onSuccess(String result) {
          if ("bar" == result) {
            //Do something if it resulted in "bar"
          } else {
            //Do something if it was some other String
          }
        }
      });
      //#onSuccess
    }
    {
      Future<String> future = Futures.failed(new IllegalStateException("OHNOES"), system.dispatcher());
      //#onFailure
      future.onFailure(new OnFailure() {
        public void onFailure(Throwable failure) {
          if (failure instanceof IllegalStateException) {
            //Do something if it was this particular failure
          } else {
            //Do something if it was some other failure
          }
        }
      });
      //#onFailure
    }
    {
      Future<String> future = Futures.successful("foo", system.dispatcher());
      //#onComplete
      future.onComplete(new OnComplete<String>() {
          public void onComplete(Throwable failure, String result) {
              if (failure != null) {
                  //We got a failure, handle it here
              } else {
                  // We got a result, do something with it
              }
          }
      });
      //#onComplete
    }
  }

  @Test
  public void useOrAndZip() throws Exception {
    {
      //#zip
      Future<String> future1 = Futures.successful("foo", system.dispatcher());
      Future<String> future2 = Futures.successful("bar", system.dispatcher());
      Future<String> future3 = future1.zip(future2).map(new Mapper<scala.Tuple2<String, String>, String>() {
        public String apply(scala.Tuple2<String, String> zipped) {
          return zipped._1() + " " + zipped._2();
        }
      });

      String result = Await.result(future3, Duration.create(1, SECONDS));
      assertEquals("foo bar", result);
      //#zip
    }

    {
      //#fallback-to
      Future<String> future1 = Futures.failed(new IllegalStateException("OHNOES1"), system.dispatcher());
      Future<String> future2 = Futures.failed(new IllegalStateException("OHNOES2"), system.dispatcher());
      Future<String> future3 = Futures.successful("bar", system.dispatcher());
      Future<String> future4 = future1.fallbackTo(future2).fallbackTo(future3); // Will have "bar" in this case
      String result = Await.result(future4, Duration.create(1, SECONDS));
      assertEquals("bar", result);
      //#fallback-to
    }

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
