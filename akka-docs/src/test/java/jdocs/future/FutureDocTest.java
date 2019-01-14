/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.future;

// #imports1
import akka.dispatch.*;
import jdocs.AbstractJavaTest;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Await;
import scala.concurrent.Promise;
import akka.util.Timeout;

// #imports1

// #imports2
import java.time.Duration;
import java.util.concurrent.*;

import scala.util.Try;

import akka.japi.Function;

import static akka.dispatch.Futures.future;
import static java.util.concurrent.TimeUnit.SECONDS;

// #imports2

// #imports3
import static akka.dispatch.Futures.sequence;

// #imports3

// #imports4
import static akka.dispatch.Futures.traverse;

// #imports4

// #imports5
import akka.japi.Function2;
import static akka.dispatch.Futures.fold;

// #imports5

// #imports6
import static akka.dispatch.Futures.reduce;

// #imports6

// #imports7
import static akka.pattern.Patterns.after;

import java.util.Arrays;
// #imports7

// #imports8

import static akka.pattern.Patterns.retry;

// #imports8

// #imports-ask
import static akka.pattern.Patterns.ask;
// #imports-ask
// #imports-pipe
import static akka.pattern.Patterns.pipe;
// #imports-pipe

import java.util.ArrayList;
import java.util.List;

import scala.compat.java8.FutureConverters;

import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.ClassRule;
import org.junit.Test;

import akka.testkit.AkkaSpec;
import akka.actor.Status.Failure;
import akka.actor.ActorSystem;
import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.pattern.Patterns;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.*;

public class FutureDocTest extends AbstractJavaTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("FutureDocTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  public static final class PrintResult<T> extends OnSuccess<T> {
    @Override
    public final void onSuccess(T t) {
      // print t
    }
  }

  public static final class Demo {
    // #print-result
    public static final class PrintResult<T> extends OnSuccess<T> {
      @Override
      public final void onSuccess(T t) {
        System.out.println(t);
      }
    }
    // #print-result
  }

  // #pipe-to-usage
  public class ActorUsingPipeTo extends AbstractActor {
    ActorRef target;
    Duration timeout;

    ActorUsingPipeTo(ActorRef target) {
      this.target = target;
      this.timeout = Duration.ofSeconds(5);
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(
              String.class,
              msg -> {
                CompletableFuture<Object> fut =
                    ask(target, "some message", timeout).toCompletableFuture();

                // the pipe pattern
                pipe(fut, getContext().dispatcher()).to(getSender());
              })
          .build();
    }
  }
  // #pipe-to-usage

  // #pipe-to-returned-data
  public class UserData {
    final String data;

    UserData(String data) {
      this.data = data;
    }
  }

  public class UserActivity {
    final String activity;

    UserActivity(String activity) {
      this.activity = activity;
    }
  }
  // #pipe-to-returned-data

  // #pipe-to-user-data-actor
  public class UserDataActor extends AbstractActor {
    UserData internalData;

    UserDataActor() {
      this.internalData = new UserData("initial data");
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(GetFromUserDataActor.class, msg -> sender().tell(internalData, self()))
          .build();
    }
  }

  public class GetFromUserDataActor {}
  // #pipe-to-user-data-actor

  // #pipe-to-user-activity-actor
  interface UserActivityRepository {
    CompletableFuture<ArrayList<UserActivity>> queryHistoricalActivities(String userId);
  }

  public class UserActivityActor extends AbstractActor {
    String userId;
    UserActivityRepository repository;

    UserActivityActor(String userId, UserActivityRepository repository) {
      this.userId = userId;
      this.repository = repository;
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(
              GetFromUserActivityActor.class,
              msg -> {
                CompletableFuture<ArrayList<UserActivity>> fut =
                    repository.queryHistoricalActivities(userId);

                pipe(fut, getContext().dispatcher()).to(sender());
              })
          .build();
    }
  }

  public class GetFromUserActivityActor {}
  // #pipe-to-user-activity-actor

  // #pipe-to-proxy-actor
  public class UserProxyActor extends AbstractActor {
    ActorRef userActor;
    ActorRef userActivityActor;
    Duration timeout = Duration.ofSeconds(5);

    UserProxyActor(ActorRef userActor, ActorRef userActivityActor) {
      this.userActor = userActor;
      this.userActivityActor = userActivityActor;
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(
              GetUserData.class,
              msg -> {
                CompletableFuture<Object> fut =
                    ask(userActor, new GetUserData(), timeout).toCompletableFuture();

                pipe(fut, getContext().dispatcher());
              })
          .match(
              GetUserActivities.class,
              msg -> {
                CompletableFuture<Object> fut =
                    ask(userActivityActor, new GetFromUserActivityActor(), timeout)
                        .toCompletableFuture();

                pipe(fut, getContext().dispatcher()).to(sender());
              })
          .build();
    }
  }
  // #pipe-to-proxy-actor

  // #pipe-to-proxy-messages
  public class GetUserData {}

  public class GetUserActivities {}
  // #pipe-to-proxy-messages

  @SuppressWarnings("unchecked")
  @Test
  public void useCustomExecutionContext() throws Exception {
    ExecutorService yourExecutorServiceGoesHere = Executors.newSingleThreadExecutor();
    // #diy-execution-context
    ExecutionContext ec = ExecutionContexts.fromExecutorService(yourExecutorServiceGoesHere);

    // Use ec with your Futures
    Future<String> f1 = Futures.successful("foo");

    // Then you shut down the ExecutorService at the end of your application.
    yourExecutorServiceGoesHere.shutdown();
    // #diy-execution-context
  }

  @Test
  public void useBlockingFromActor() throws Exception {
    ActorRef actor = system.actorOf(Props.create(MyActor.class));
    String msg = "hello";
    // #ask-blocking
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    Future<Object> future = Patterns.ask(actor, msg, timeout);
    String result = (String) Await.result(future, timeout.duration());
    // #ask-blocking

    assertEquals("HELLO", result);
  }

  @Test
  public void useFutureEval() throws Exception {
    // #future-eval
    Future<String> f =
        future(
            new Callable<String>() {
              public String call() {
                return "Hello" + "World";
              }
            },
            system.dispatcher());

    f.onComplete(new PrintResult<Try<String>>(), system.dispatcher());
    // #future-eval
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    String result = (String) Await.result(f, timeout.duration());
    assertEquals("HelloWorld", result);
  }

  @Test
  public void useMap() throws Exception {
    // #map
    final ExecutionContext ec = system.dispatcher();

    Future<String> f1 =
        future(
            new Callable<String>() {
              public String call() {
                return "Hello" + "World";
              }
            },
            ec);

    Future<Integer> f2 =
        f1.map(
            new Mapper<String, Integer>() {
              public Integer apply(String s) {
                return s.length();
              }
            },
            ec);

    f2.onComplete(new PrintResult<Try<Integer>>(), system.dispatcher());
    // #map
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    int result = Await.result(f2, timeout.duration());
    assertEquals(10, result);
  }

  @Test
  public void useFlatMap() throws Exception {
    // #flat-map
    final ExecutionContext ec = system.dispatcher();

    Future<String> f1 =
        future(
            new Callable<String>() {
              public String call() {
                return "Hello" + "World";
              }
            },
            ec);

    Future<Integer> f2 =
        f1.flatMap(
            new Mapper<String, Future<Integer>>() {
              public Future<Integer> apply(final String s) {
                return future(
                    new Callable<Integer>() {
                      public Integer call() {
                        return s.length();
                      }
                    },
                    ec);
              }
            },
            ec);

    f2.onComplete(new PrintResult<Try<Integer>>(), system.dispatcher());
    // #flat-map
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    int result = Await.result(f2, timeout.duration());
    assertEquals(10, result);
  }

  @Test
  public void useSequence() throws Exception {
    List<Future<Integer>> source = new ArrayList<Future<Integer>>();
    source.add(Futures.successful(1));
    source.add(Futures.successful(2));

    // #sequence
    final ExecutionContext ec = system.dispatcher();
    // Some source generating a sequence of Future<Integer>:s
    Iterable<Future<Integer>> listOfFutureInts = source;

    // now we have a Future[Iterable[Integer]]
    Future<Iterable<Integer>> futureListOfInts = sequence(listOfFutureInts, ec);

    // Find the sum of the odd numbers
    Future<Long> futureSum =
        futureListOfInts.map(
            new Mapper<Iterable<Integer>, Long>() {
              public Long apply(Iterable<Integer> ints) {
                long sum = 0;
                for (Integer i : ints) sum += i;
                return sum;
              }
            },
            ec);

    futureSum.onComplete(new PrintResult<Try<Long>>(), system.dispatcher());
    // #sequence
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    long result = Await.result(futureSum, timeout.duration());
    assertEquals(3L, result);
  }

  @Test
  public void useTraverse() throws Exception {
    // #traverse
    final ExecutionContext ec = system.dispatcher();
    // Just a sequence of Strings
    Iterable<String> listStrings = Arrays.asList("a", "b", "c");

    Future<Iterable<String>> futureResult =
        traverse(
            listStrings,
            new Function<String, Future<String>>() {
              public Future<String> apply(final String r) {
                return future(
                    new Callable<String>() {
                      public String call() {
                        return r.toUpperCase();
                      }
                    },
                    ec);
              }
            },
            ec);

    // Returns the sequence of strings as upper case
    futureResult.onComplete(new PrintResult<Try<Iterable<String>>>(), system.dispatcher());
    // #traverse
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    Iterable<String> result = Await.result(futureResult, timeout.duration());
    assertEquals(Arrays.asList("A", "B", "C"), result);
  }

  @Test
  public void useFold() throws Exception {
    List<Future<String>> source = new ArrayList<Future<String>>();
    source.add(Futures.successful("a"));
    source.add(Futures.successful("b"));
    // #fold

    final ExecutionContext ec = system.dispatcher();

    // A sequence of Futures, in this case Strings
    Iterable<Future<String>> futures = source;

    // Start value is the empty string
    Future<String> resultFuture =
        fold(
            "",
            futures,
            new Function2<String, String, String>() {
              public String apply(String r, String t) {
                return r + t; // Just concatenate
              }
            },
            ec);

    resultFuture.onComplete(new PrintResult<Try<String>>(), system.dispatcher());
    // #fold
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    String result = Await.result(resultFuture, timeout.duration());
    assertEquals("ab", result);
  }

  @Test
  public void useReduce() throws Exception {
    List<Future<String>> source = new ArrayList<Future<String>>();
    source.add(Futures.successful("a"));
    source.add(Futures.successful("b"));
    // #reduce

    final ExecutionContext ec = system.dispatcher();
    // A sequence of Futures, in this case Strings
    Iterable<Future<String>> futures = source;

    Future<Object> resultFuture =
        reduce(
            futures,
            new Function2<Object, String, Object>() {
              public Object apply(Object r, String t) {
                return r + t; // Just concatenate
              }
            },
            ec);

    resultFuture.onComplete(new PrintResult<Try<Object>>(), system.dispatcher());
    // #reduce
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    Object result = Await.result(resultFuture, timeout.duration());

    assertEquals("ab", result);
  }

  @Test
  public void useSuccessfulAndFailedAndPromise() throws Exception {
    final ExecutionContext ec = system.dispatcher();
    // #successful
    Future<String> future = Futures.successful("Yay!");
    // #successful
    // #failed
    Future<String> otherFuture = Futures.failed(new IllegalArgumentException("Bang!"));
    // #failed
    // #promise
    Promise<String> promise = Futures.promise();
    Future<String> theFuture = promise.future();
    promise.success("hello");
    // #promise
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    Object result = Await.result(future, timeout.duration());
    assertEquals("Yay!", result);
    Throwable result2 = Await.result(otherFuture.failed(), timeout.duration());
    assertEquals("Bang!", result2.getMessage());
    String out = Await.result(theFuture, timeout.duration());
    assertEquals("hello", out);
  }

  @Test
  public void useFilter() throws Exception {
    // #filter
    final ExecutionContext ec = system.dispatcher();
    Future<Integer> future1 = Futures.successful(4);
    Future<Integer> successfulFilter =
        future1.filter(
            Filter.filterOf(
                new Function<Integer, Boolean>() {
                  public Boolean apply(Integer i) {
                    return i % 2 == 0;
                  }
                }),
            ec);

    Future<Integer> failedFilter =
        future1.filter(
            Filter.filterOf(
                new Function<Integer, Boolean>() {
                  public Boolean apply(Integer i) {
                    return i % 2 != 0;
                  }
                }),
            ec);
    // When filter fails, the returned Future will be failed with a scala.MatchError
    // #filter
  }

  public void sendToTheInternetz(String s) {}

  public void sendToIssueTracker(Throwable t) {}

  @Test
  public void useAndThen() {
    // #and-then
    final ExecutionContext ec = system.dispatcher();
    Future<String> future1 =
        Futures.successful("value")
            .andThen(
                new OnComplete<String>() {
                  public void onComplete(Throwable failure, String result) {
                    if (failure != null) sendToIssueTracker(failure);
                  }
                },
                ec)
            .andThen(
                new OnComplete<String>() {
                  public void onComplete(Throwable failure, String result) {
                    if (result != null) sendToTheInternetz(result);
                  }
                },
                ec);
    // #and-then
  }

  @Test
  public void useRecover() throws Exception {
    // #recover
    final ExecutionContext ec = system.dispatcher();

    Future<Integer> future =
        future(
                new Callable<Integer>() {
                  public Integer call() {
                    return 1 / 0;
                  }
                },
                ec)
            .recover(
                new Recover<Integer>() {
                  public Integer recover(Throwable problem) throws Throwable {
                    if (problem instanceof ArithmeticException) return 0;
                    else throw problem;
                  }
                },
                ec);

    future.onComplete(new PrintResult<Try<Integer>>(), system.dispatcher());
    // #recover
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    int result = Await.result(future, timeout.duration());
    assertEquals(result, 0);
  }

  @Test
  public void useTryRecover() throws Exception {
    // #try-recover
    final ExecutionContext ec = system.dispatcher();

    Future<Integer> future =
        future(
                new Callable<Integer>() {
                  public Integer call() {
                    return 1 / 0;
                  }
                },
                ec)
            .recoverWith(
                new Recover<Future<Integer>>() {
                  public Future<Integer> recover(Throwable problem) throws Throwable {
                    if (problem instanceof ArithmeticException) {
                      return future(
                          new Callable<Integer>() {
                            public Integer call() {
                              return 0;
                            }
                          },
                          ec);
                    } else throw problem;
                  }
                },
                ec);

    future.onComplete(new PrintResult<Try<Integer>>(), system.dispatcher());
    // #try-recover
    Timeout timeout = Timeout.create(Duration.ofSeconds(5));
    int result = Await.result(future, timeout.duration());
    assertEquals(result, 0);
  }

  @Test
  public void useOnOnComplete() throws Exception {
    {
      Future<String> future = Futures.successful("foo");

      // #onComplete
      final ExecutionContext ec = system.dispatcher();

      future.onComplete(
          new OnComplete<String>() {
            public void onComplete(Throwable failure, String result) {
              if (failure != null) {
                // We got a failure, handle it here
              } else {
                // We got a result, do something with it
              }
            }
          },
          ec);
      // #onComplete
    }
  }

  @Test
  public void useOrAndZip() throws Exception {
    {
      // #zip
      final ExecutionContext ec = system.dispatcher();
      Future<String> future1 = Futures.successful("foo");
      Future<String> future2 = Futures.successful("bar");
      Future<String> future3 =
          future1
              .zip(future2)
              .map(
                  new Mapper<scala.Tuple2<String, String>, String>() {
                    public String apply(scala.Tuple2<String, String> zipped) {
                      return zipped._1() + " " + zipped._2();
                    }
                  },
                  ec);

      future3.onComplete(new PrintResult<Try<String>>(), system.dispatcher());
      // #zip
      Timeout timeout = Timeout.create(Duration.ofSeconds(5));
      String result = Await.result(future3, timeout.duration());
      assertEquals("foo bar", result);
    }

    {
      // #fallback-to
      Future<String> future1 = Futures.failed(new IllegalStateException("OHNOES1"));
      Future<String> future2 = Futures.failed(new IllegalStateException("OHNOES2"));
      Future<String> future3 = Futures.successful("bar");
      // Will have "bar" in this case
      Future<String> future4 = future1.fallbackTo(future2).fallbackTo(future3);
      future4.onComplete(new PrintResult<Try<String>>(), system.dispatcher());
      // #fallback-to
      Timeout timeout = Timeout.create(Duration.ofSeconds(5));
      String result = Await.result(future4, timeout.duration());
      assertEquals("bar", result);
    }
  }

  @Test(expected = IllegalStateException.class)
  @SuppressWarnings("unchecked")
  public void useAfter() throws Exception {
    // #after
    final ExecutionContext ec = system.dispatcher();
    Future<String> failExc = Futures.failed(new IllegalStateException("OHNOES1"));
    Timeout delay = Timeout.create(Duration.ofMillis(200));
    Future<String> delayed = Patterns.after(delay.duration(), system.scheduler(), ec, failExc);
    Future<String> future =
        future(
            new Callable<String>() {
              public String call() throws InterruptedException {
                Thread.sleep(1000);
                return "foo";
              }
            },
            ec);
    Future<String> result =
        Futures.firstCompletedOf(Arrays.<Future<String>>asList(future, delayed), ec);
    // #after
    Timeout timeout = Timeout.create(Duration.ofSeconds(2));
    Await.result(result, timeout.duration());
  }

  @Test
  public void useRetry() throws Exception {

    // #retry
    final ExecutionContext ec = system.dispatcher();
    Callable<CompletionStage<String>> attempt = () -> CompletableFuture.completedFuture("test");
    CompletionStage<String> retriedFuture =
        retry(attempt, 3, java.time.Duration.ofMillis(200), system.scheduler(), ec);
    // #retry

    retriedFuture.toCompletableFuture().get(2, SECONDS);
  }

  @Test
  public void thenApplyCompletionThread() throws Exception {
    // #apply-completion-thread
    final ExecutionContext ec = system.dispatcher();
    final CountDownLatch countDownLatch = new CountDownLatch(1);

    Future<String> scalaFuture =
        Futures.future(
            () -> {
              assertThat(
                  Thread.currentThread().getName(),
                  containsString("akka.actor.default-dispatcher"));
              countDownLatch.await(); // do not complete yet
              return "hello";
            },
            ec);

    CompletionStage<String> fromScalaFuture =
        FutureConverters.toJava(scalaFuture)
            .thenApply(
                s -> { // 1
                  assertThat(
                      Thread.currentThread().getName(), containsString("ForkJoinPool.commonPool"));
                  return s;
                })
            .thenApply(
                s -> { // 2
                  assertThat(
                      Thread.currentThread().getName(), containsString("ForkJoinPool.commonPool"));
                  return s;
                })
            .thenApply(
                s -> { // 3
                  assertThat(
                      Thread.currentThread().getName(), containsString("ForkJoinPool.commonPool"));
                  return s;
                });

    countDownLatch.countDown(); // complete scalaFuture
    // #apply-completion-thread

    fromScalaFuture.toCompletableFuture().get(2, SECONDS);
  }

  @Test
  public void thenApplyMainThread() throws Exception {
    final ExecutionContext ec = system.dispatcher();

    // #apply-main-thread
    Future<String> scalaFuture =
        Futures.future(
            () -> {
              assertThat(
                  Thread.currentThread().getName(),
                  containsString("akka.actor.default-dispatcher"));
              return "hello";
            },
            ec);

    CompletionStage<String> completedStage =
        FutureConverters.toJava(scalaFuture)
            .thenApply(
                s -> { // 1
                  assertThat(
                      Thread.currentThread().getName(), containsString("ForkJoinPool.commonPool"));
                  return s;
                });

    completedStage.toCompletableFuture().get(2, SECONDS); // complete current CompletionStage
    final String currentThread = Thread.currentThread().getName();

    CompletionStage<String> stage2 =
        completedStage
            .thenApply(
                s -> { // 2
                  assertThat(Thread.currentThread().getName(), is(currentThread));
                  return s;
                })
            .thenApply(
                s -> { // 3
                  assertThat(Thread.currentThread().getName(), is(currentThread));
                  return s;
                });
    // #apply-main-thread

    stage2.toCompletableFuture().get(2, SECONDS);
  }

  @Test
  public void thenApplyAsyncDefault() throws Exception {
    final ExecutionContext ec = system.dispatcher();

    Future<String> scalaFuture =
        Futures.future(
            () -> {
              assertThat(
                  Thread.currentThread().getName(),
                  containsString("akka.actor.default-dispatcher"));
              return "hello";
            },
            ec);

    // #apply-async-default
    CompletionStage<String> fromScalaFuture =
        FutureConverters.toJava(scalaFuture)
            .thenApplyAsync(
                s -> { // 1
                  assertThat(
                      Thread.currentThread().getName(), containsString("ForkJoinPool.commonPool"));
                  return s;
                })
            .thenApplyAsync(
                s -> { // 2
                  assertThat(
                      Thread.currentThread().getName(), containsString("ForkJoinPool.commonPool"));
                  return s;
                })
            .thenApplyAsync(
                s -> { // 3
                  assertThat(
                      Thread.currentThread().getName(), containsString("ForkJoinPool.commonPool"));
                  return s;
                });
    // #apply-async-default

    fromScalaFuture.toCompletableFuture().get(2, SECONDS);
  }

  @Test
  public void thenApplyAsyncExecutor() throws Exception {
    final ExecutionContext ec = system.dispatcher();

    Future<String> scalaFuture =
        Futures.future(
            () -> {
              assertThat(
                  Thread.currentThread().getName(),
                  containsString("akka.actor.default-dispatcher"));
              return "hello";
            },
            ec);

    // #apply-async-executor
    final Executor ex = system.dispatcher();

    CompletionStage<String> fromScalaFuture =
        FutureConverters.toJava(scalaFuture)
            .thenApplyAsync(
                s -> {
                  assertThat(
                      Thread.currentThread().getName(),
                      containsString("akka.actor.default-dispatcher"));
                  return s;
                },
                ex)
            .thenApplyAsync(
                s -> {
                  assertThat(
                      Thread.currentThread().getName(),
                      containsString("akka.actor.default-dispatcher"));
                  return s;
                },
                ex)
            .thenApplyAsync(
                s -> {
                  assertThat(
                      Thread.currentThread().getName(),
                      containsString("akka.actor.default-dispatcher"));
                  return s;
                },
                ex);
    // #apply-async-executor

    fromScalaFuture.toCompletableFuture().get(2, SECONDS);
  }

  public static class MyActor extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(
              String.class,
              msg -> {
                getSender().tell(msg.toUpperCase(), getSelf());
              })
          .match(
              Integer.class,
              i -> {
                if (i < 0) {
                  getSender()
                      .tell(
                          new Failure(new ArithmeticException("Negative values not supported")),
                          getSelf());
                } else {
                  getSender().tell(i, getSelf());
                }
              })
          .build();
    }
  }
}
