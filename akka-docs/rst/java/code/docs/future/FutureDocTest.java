/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.future;

//#imports1
import akka.dispatch.*;
import docs.AbstractJavaTest;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Await;
import scala.concurrent.Promise;
import akka.util.Timeout;
//#imports1

//#imports2
import scala.concurrent.duration.Duration;
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

//#imports8
import static akka.pattern.Patterns.after;
import java.util.Arrays;
//#imports8

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.ClassRule;
import org.junit.Test;

import akka.testkit.AkkaSpec;
import akka.actor.Status.Failure;
import akka.actor.ActorSystem;
import akka.actor.UntypedActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.pattern.Patterns;

import static org.junit.Assert.*;

public class FutureDocTest extends AbstractJavaTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("FutureDocTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  public final static class PrintResult<T> extends OnSuccess<T> {
    @Override public final void onSuccess(T t) {
      // print t
    }
  }

  public final static class Demo {
    //#print-result
    public final static class PrintResult<T> extends OnSuccess<T> {
      @Override public final void onSuccess(T t) {
        System.out.println(t);
      }
    }
    //#print-result
  }
  @SuppressWarnings("unchecked") @Test public void useCustomExecutionContext() throws Exception {
    ExecutorService yourExecutorServiceGoesHere = Executors.newSingleThreadExecutor();
    //#diy-execution-context
    ExecutionContext ec =
      ExecutionContexts.fromExecutorService(yourExecutorServiceGoesHere);

    //Use ec with your Futures
    Future<String> f1 = Futures.successful("foo");

    // Then you shut down the ExecutorService at the end of your application.
    yourExecutorServiceGoesHere.shutdown();
    //#diy-execution-context
  }

  @Test
  public void useBlockingFromActor() throws Exception {
    ActorRef actor = system.actorOf(Props.create(MyActor.class));
    String msg = "hello";
    //#ask-blocking
    Timeout timeout = new Timeout(Duration.create(5, "seconds"));
    Future<Object> future = Patterns.ask(actor, msg, timeout);
    String result = (String) Await.result(future, timeout.duration());
    //#ask-blocking
    //#pipe-to
    akka.pattern.Patterns.pipe(future, system.dispatcher()).to(actor);
    //#pipe-to
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

    f.onSuccess(new PrintResult<String>(), system.dispatcher());
    //#future-eval
    String result = (String) Await.result(f, Duration.create(5, SECONDS));
    assertEquals("HelloWorld", result);
  }

  @Test
  public void useMap() throws Exception {
    //#map
    final ExecutionContext ec = system.dispatcher();

    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, ec);

    Future<Integer> f2 = f1.map(new Mapper<String, Integer>() {
      public Integer apply(String s) {
        return s.length();
      }
    }, ec);

    f2.onSuccess(new PrintResult<Integer>(), system.dispatcher());
    //#map
    int result = Await.result(f2, Duration.create(5, SECONDS));
    assertEquals(10, result);
  }

  @Test
  public void useMap2() throws Exception {
    //#map2
    final ExecutionContext ec = system.dispatcher();

    Future<String> f1 = future(new Callable<String>() {
      public String call() throws Exception {
        Thread.sleep(100);
        return "Hello" + "World";
      }
    }, ec);

    Future<Integer> f2 = f1.map(new Mapper<String, Integer>() {
      public Integer apply(String s) {
        return s.length();
      }
    }, ec);

    f2.onSuccess(new PrintResult<Integer>(), system.dispatcher());
    //#map2
    int result = Await.result(f2, Duration.create(5, SECONDS));
    assertEquals(10, result);
  }

  @Test
  public void useMap3() throws Exception {
    //#map3
    final ExecutionContext ec = system.dispatcher();

    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, ec);

    // Thread.sleep is only here to prove a point
    Thread.sleep(100); // Do not use this in your code

    Future<Integer> f2 = f1.map(new Mapper<String, Integer>() {
      public Integer apply(String s) {
        return s.length();
      }
    }, ec);

    f2.onSuccess(new PrintResult<Integer>(), system.dispatcher());
    //#map3
    int result = Await.result(f2, Duration.create(5, SECONDS));
    assertEquals(10, result);
  }

  @Test
  public void useFlatMap() throws Exception {
    //#flat-map
    final ExecutionContext ec = system.dispatcher();

    Future<String> f1 = future(new Callable<String>() {
      public String call() {
        return "Hello" + "World";
      }
    }, ec);

    Future<Integer> f2 = f1.flatMap(new Mapper<String, Future<Integer>>() {
      public Future<Integer> apply(final String s) {
        return future(new Callable<Integer>() {
          public Integer call() {
            return s.length();
          }
        }, ec);
      }
    }, ec);

    f2.onSuccess(new PrintResult<Integer>(), system.dispatcher());
    //#flat-map
    int result = Await.result(f2, Duration.create(5, SECONDS));
    assertEquals(10, result);
  }

  @Test
  public void useSequence() throws Exception {
    List<Future<Integer>> source = new ArrayList<Future<Integer>>();
    source.add(Futures.successful(1));
    source.add(Futures.successful(2));

    //#sequence
    final ExecutionContext ec = system.dispatcher();
    //Some source generating a sequence of Future<Integer>:s
    Iterable<Future<Integer>> listOfFutureInts = source;

    // now we have a Future[Iterable[Integer]]
    Future<Iterable<Integer>> futureListOfInts = sequence(listOfFutureInts, ec);

    // Find the sum of the odd numbers
    Future<Long> futureSum = futureListOfInts.map(
      new Mapper<Iterable<Integer>, Long>() {
        public Long apply(Iterable<Integer> ints) {
          long sum = 0;
          for (Integer i : ints)
            sum += i;
          return sum;
        }
      }, ec);

    futureSum.onSuccess(new PrintResult<Long>(), system.dispatcher());
    //#sequence
    long result = Await.result(futureSum, Duration.create(5, SECONDS));
    assertEquals(3L, result);
  }

  @Test
  public void useTraverse() throws Exception {
    //#traverse
    final ExecutionContext ec = system.dispatcher();
    //Just a sequence of Strings
    Iterable<String> listStrings = Arrays.asList("a", "b", "c");

    Future<Iterable<String>> futureResult = traverse(listStrings,
      new Function<String, Future<String>>() {
        public Future<String> apply(final String r) {
          return future(new Callable<String>() {
            public String call() {
              return r.toUpperCase();
            }
          }, ec);
        }
      }, ec);

    //Returns the sequence of strings as upper case
    futureResult.onSuccess(new PrintResult<Iterable<String>>(), system.dispatcher());
    //#traverse
    Iterable<String> result = Await.result(futureResult, Duration.create(5, SECONDS));
    assertEquals(Arrays.asList("A", "B", "C"), result);
  }

  @Test
  public void useFold() throws Exception {
    List<Future<String>> source = new ArrayList<Future<String>>();
    source.add(Futures.successful("a"));
    source.add(Futures.successful("b"));
    //#fold

    final ExecutionContext ec = system.dispatcher();

    //A sequence of Futures, in this case Strings
    Iterable<Future<String>> futures = source;

    //Start value is the empty string
    Future<String> resultFuture = fold("", futures,
      new Function2<String, String, String>() {
        public String apply(String r, String t) {
          return r + t; //Just concatenate
        }
      }, ec);

    resultFuture.onSuccess(new PrintResult<String>(), system.dispatcher());
    //#fold
    String result = Await.result(resultFuture, Duration.create(5, SECONDS));
    assertEquals("ab", result);
  }

  @Test
  public void useReduce() throws Exception {
    List<Future<String>> source = new ArrayList<Future<String>>();
    source.add(Futures.successful("a"));
    source.add(Futures.successful("b"));
    //#reduce

    final ExecutionContext ec = system.dispatcher();
    //A sequence of Futures, in this case Strings
    Iterable<Future<String>> futures = source;

    Future<Object> resultFuture = reduce(futures,
      new Function2<Object, String, Object>() {
        public Object apply(Object r, String t) {
          return r + t; //Just concatenate
        }
      }, ec);

    resultFuture.onSuccess(new PrintResult<Object>(), system.dispatcher());
    //#reduce
    Object result = Await.result(resultFuture, Duration.create(5, SECONDS));

    assertEquals("ab", result);
  }

  @Test
  public void useSuccessfulAndFailedAndPromise() throws Exception {
    final ExecutionContext ec = system.dispatcher();
    //#successful
    Future<String> future = Futures.successful("Yay!");
    //#successful
    //#failed
    Future<String> otherFuture = Futures.failed(
      new IllegalArgumentException("Bang!"));
    //#failed
    //#promise
    Promise<String> promise = Futures.promise();
    Future<String> theFuture = promise.future();
    promise.success("hello");
    //#promise
    Object result = Await.result(future, Duration.create(5, SECONDS));
    assertEquals("Yay!", result);
    Throwable result2 = Await.result(otherFuture.failed(),
      Duration.create(5, SECONDS));
    assertEquals("Bang!", result2.getMessage());
    String out = Await.result(theFuture, Duration.create(5, SECONDS));
    assertEquals("hello", out);
  }

  @Test
  public void useFilter() throws Exception {
    //#filter
    final ExecutionContext ec = system.dispatcher();
    Future<Integer> future1 = Futures.successful(4);
    Future<Integer> successfulFilter = future1.filter(Filter.filterOf(
      new Function<Integer, Boolean>() {
        public Boolean apply(Integer i) {
          return i % 2 == 0;
        }
      }), ec);

    Future<Integer> failedFilter = future1.filter(Filter.filterOf(
      new Function<Integer, Boolean>() {
        public Boolean apply(Integer i) {
          return i % 2 != 0;
        }
      }), ec);
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
    final ExecutionContext ec = system.dispatcher();
    Future<String> future1 = Futures.successful("value").andThen(
      new OnComplete<String>() {
        public void onComplete(Throwable failure, String result) {
          if (failure != null)
            sendToIssueTracker(failure);
        }
      }, ec).andThen(new OnComplete<String>() {
      public void onComplete(Throwable failure, String result) {
        if (result != null)
          sendToTheInternetz(result);
      }
    }, ec);
    //#and-then
  }

  @Test
  public void useRecover() throws Exception {
    //#recover
    final ExecutionContext ec = system.dispatcher();

    Future<Integer> future = future(new Callable<Integer>() {
      public Integer call() {
        return 1 / 0;
      }
    }, ec).recover(new Recover<Integer>() {
      public Integer recover(Throwable problem) throws Throwable {
        if (problem instanceof ArithmeticException)
          return 0;
        else
          throw problem;
      }
    }, ec);

    future.onSuccess(new PrintResult<Integer>(), system.dispatcher());
    //#recover
    int result = Await.result(future, Duration.create(5, SECONDS));
    assertEquals(result, 0);
  }

  @Test
  public void useTryRecover() throws Exception {
    //#try-recover
    final ExecutionContext ec = system.dispatcher();

    Future<Integer> future = future(new Callable<Integer>() {
      public Integer call() {
        return 1 / 0;
      }
    }, ec).recoverWith(new Recover<Future<Integer>>() {
      public Future<Integer> recover(Throwable problem) throws Throwable {
        if (problem instanceof ArithmeticException) {
          return future(new Callable<Integer>() {
            public Integer call() {
              return 0;
            }
          }, ec);
        } else
          throw problem;
      }
    }, ec);

    future.onSuccess(new PrintResult<Integer>(), system.dispatcher());
    //#try-recover
    int result = Await.result(future, Duration.create(5, SECONDS));
    assertEquals(result, 0);
  }

  @Test
  public void useOnSuccessOnFailureAndOnComplete() throws Exception {
    {
      Future<String> future = Futures.successful("foo");

      //#onSuccess
      final ExecutionContext ec = system.dispatcher();

      future.onSuccess(new OnSuccess<String>() {
        public void onSuccess(String result) {
          if ("bar" == result) {
            //Do something if it resulted in "bar"
          } else {
            //Do something if it was some other String
          }
        }
      }, ec);
      //#onSuccess
    }
    {
      Future<String> future = Futures.failed(new IllegalStateException("OHNOES"));
      //#onFailure
      final ExecutionContext ec = system.dispatcher();

      future.onFailure(new OnFailure() {
        public void onFailure(Throwable failure) {
          if (failure instanceof IllegalStateException) {
            //Do something if it was this particular failure
          } else {
            //Do something if it was some other failure
          }
        }
      }, ec);
      //#onFailure
    }
    {
      Future<String> future = Futures.successful("foo");
      //#onComplete
      final ExecutionContext ec = system.dispatcher();

      future.onComplete(new OnComplete<String>() {
        public void onComplete(Throwable failure, String result) {
          if (failure != null) {
            //We got a failure, handle it here
          } else {
            // We got a result, do something with it
          }
        }
      }, ec);
      //#onComplete
    }
  }

  @Test
  public void useOrAndZip() throws Exception {
    {
      //#zip
      final ExecutionContext ec = system.dispatcher();
      Future<String> future1 = Futures.successful("foo");
      Future<String> future2 = Futures.successful("bar");
      Future<String> future3 = future1.zip(future2).map(
        new Mapper<scala.Tuple2<String, String>, String>() {
          public String apply(scala.Tuple2<String, String> zipped) {
            return zipped._1() + " " + zipped._2();
          }
        }, ec);

      future3.onSuccess(new PrintResult<String>(), system.dispatcher());
      //#zip
      String result = Await.result(future3, Duration.create(5, SECONDS));
      assertEquals("foo bar", result);
    }

    {
      //#fallback-to
      Future<String> future1 = Futures.failed(new IllegalStateException("OHNOES1"));
      Future<String> future2 = Futures.failed(new IllegalStateException("OHNOES2"));
      Future<String> future3 = Futures.successful("bar");
      // Will have "bar" in this case
      Future<String> future4 = future1.fallbackTo(future2).fallbackTo(future3);
      future4.onSuccess(new PrintResult<String>(), system.dispatcher());
      //#fallback-to
      String result = Await.result(future4, Duration.create(5, SECONDS));
      assertEquals("bar", result);
    }

  }

  @Test(expected = IllegalStateException.class)
  @SuppressWarnings("unchecked")
  public void useAfter() throws Exception {
    //#after
    final ExecutionContext ec = system.dispatcher();
    Future<String> failExc = Futures.failed(new IllegalStateException("OHNOES1"));
    Future<String> delayed = Patterns.after(Duration.create(200, "millis"),
      system.scheduler(), ec,  failExc);
    Future<String> future = future(new Callable<String>() {
      public String call() throws InterruptedException {
        Thread.sleep(1000);
        return "foo";
      }
    }, ec);
    Future<String> result = Futures.firstCompletedOf(
      Arrays.<Future<String>>asList(future, delayed), ec);
    //#after
    Await.result(result, Duration.create(2, SECONDS));
  }

  public static class MyActor extends UntypedActor {
    public void onReceive(Object message) {
      if (message instanceof String) {
        getSender().tell(((String) message).toUpperCase(), getSelf());
      } else if (message instanceof Integer) {
        int i = ((Integer) message).intValue();
        if (i < 0) {
          getSender().tell(new Failure(new ArithmeticException("Negative values not supported")), getSelf());
        } else {
          getSender().tell(i, getSelf());
        }
      } else {
        unhandled(message);
      }
    }
  }
}
