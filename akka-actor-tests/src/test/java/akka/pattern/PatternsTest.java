/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern;

import akka.actor.*;
import akka.dispatch.Futures;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import akka.testkit.TestLatch;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.concurrent.*;

import static akka.pattern.Patterns.ask;
import static akka.pattern.Patterns.pipe;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;

/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
public class PatternsTest extends JUnitSuite {

    public static final class ExplicitAskTestActor extends AbstractActor {

        public static final class Message implements NoSerializationVerificationNeeded {

            public Message(final String text, ActorRef replyTo) {
                this.text = text;
                this.replyTo = replyTo;
            }

            public final String text;
            public final ActorRef replyTo;
        }

        public Receive createReceive() {
            return receiveBuilder()
                    .match(Message.class, message -> message.replyTo.tell(message.text, getSelf()))
                    .build();
        }
    }

    public static final class StopActor extends AbstractActor {

        public static final class StopMessage implements NoSerializationVerificationNeeded {
            public final TestLatch latch;
            public final FiniteDuration duration;

            public StopMessage(TestLatch latch, FiniteDuration duration) {
                this.latch = latch;
                this.duration = duration;
            }
        }

        @Override
        public Receive createReceive() {
            return receiveBuilder()
                    .match(StopMessage.class, message -> Await.ready(message.latch, message.duration))
                    .build();
        }
    }

    @ClassRule
    public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("JavaAPI",
            AkkaSpec.testConf());

    private final ActorSystem system = actorSystemResource.getSystem();

    private final ExecutionContext ec = system.dispatcher();


    @Test
    public void useAsk() throws Exception {
        ActorRef testActor = system.actorOf(Props.create(JavaAPITestActor.class), "test");
        assertEquals("Ask should return expected answer",
                JavaAPITestActor.ANSWER, Await.result(ask(testActor, "hey!", 3000), Duration.create(3, "seconds")));
    }

    @Test
    public void useAskWithActorSelection() throws Exception {
        ActorRef testActor = system.actorOf(Props.create(JavaAPITestActor.class), "test2");
        ActorSelection selection = system.actorSelection("/user/test2");
        ActorIdentity id = (ActorIdentity) Await.result(ask(selection, new Identify("yo!"), 3000), Duration.create(3, "seconds"));
        assertEquals("Ask (Identify) should return the proper ActorIdentity", testActor, id.getActorRef().get());
    }

    @Test
    public void testCSAsk() throws Exception {
        ActorRef target = system.actorOf(Props.create(JavaAPITestActor.class));
        CompletionStage<String> result = PatternsCS.ask(target, "hello", 3000).thenApply(o -> (String)o);

        String actual = result.toCompletableFuture().get(3, SECONDS);
        assertEquals(JavaAPITestActor.ANSWER, actual);
    }

    @Test
    public void testCSAskWithActorSelection() throws Exception {
        ActorRef target = system.actorOf(Props.create(JavaAPITestActor.class), "test3");

        ActorSelection selection = system.actorSelection("/user/test3");
        ActorIdentity id = PatternsCS.ask(selection, new Identify("hello"), 3000)
                .toCompletableFuture()
                .thenApply(o -> (ActorIdentity)o)
                .get(3, SECONDS);

        assertEquals(target, id.getActorRef().get());
    }

    @Test
    public void testCSAskWithReplyToTimeout() throws Exception {
        final String expected = "hello";

        final ActorRef echo = system.actorOf(Props.create(ExplicitAskTestActor.class));
        final CompletionStage<String> response = PatternsCS
                .askWithReplyTo(
                        echo,
                        replyTo -> new ExplicitAskTestActor.Message(expected, replyTo),
                        Timeout.apply(3, SECONDS))
                .thenApply(o -> (String)o);

        final String actual = response.toCompletableFuture().get(3, SECONDS);
        assertEquals(expected, actual);
    }


    @Test
    public void testCSAskWithReplyToTimeoutMillis() throws Exception {
        final String expected = "hello";

        final ActorRef echo = system.actorOf(Props.create(ExplicitAskTestActor.class));
        final CompletionStage<String> response = PatternsCS
                .askWithReplyTo(
                        echo,
                        replyTo -> new ExplicitAskTestActor.Message(expected, replyTo),
                        3000)
                .thenApply(o -> (String)o);

        final String actual = response.toCompletableFuture().get(3, SECONDS);
        assertEquals(expected, actual);
    }

    @Test
    public void testCSAskSelectionWithReplyToTimeoutMillis() throws Exception {
        final String expected = "hello";

        final ActorRef echo = system.actorOf(Props.create(ExplicitAskTestActor.class));
        final ActorSelection selection = system.actorSelection(echo.path());
        final CompletionStage<String> response = PatternsCS
                .askWithReplyTo(
                        selection,
                        replyTo -> new ExplicitAskTestActor.Message(expected, replyTo),
                        3000)
                .thenApply(o -> (String)o);

        final String actual = response.toCompletableFuture().get(3, SECONDS);
        assertEquals(expected, actual);
    }

    @Test
    public void testAskWithReplyToTimeoutMillis() throws Exception {
        final String expected = "hello";

        final ActorRef echo = system.actorOf(Props.create(ExplicitAskTestActor.class));
        final Future<Object> response = Patterns
                .askWithReplyTo(
                        echo,
                        replyTo -> new ExplicitAskTestActor.Message(expected, replyTo),
                        3000);


        final Object actual = Await.result(response, FiniteDuration.apply(3, SECONDS));
        assertEquals(expected, actual);
    }

    @Test
    public void testAskSelectionWithReplyToTimeoutMillis() throws Exception {
        final String expected = "hello";

        final ActorRef echo = system.actorOf(Props.create(ExplicitAskTestActor.class));
        final ActorSelection selection = system.actorSelection(echo.path());
        final Future<Object> response = Patterns
                .askWithReplyTo(
                        selection,
                        replyTo -> new ExplicitAskTestActor.Message(expected, replyTo),
                        3000);


        final Object actual = Await.result(response, FiniteDuration.apply(3, SECONDS));
        assertEquals(expected, actual);
    }


    @Test
    public void testAskWithReplyToTimeout() throws Exception {
        final String expected = "hello";

        final ActorRef echo = system.actorOf(Props.create(ExplicitAskTestActor.class));
        final Future<Object> response = Patterns
                .askWithReplyTo(
                        echo,
                        replyTo -> new ExplicitAskTestActor.Message(expected, replyTo),
                        Timeout.apply(3, SECONDS));


        final Object actual = Await.result(response, FiniteDuration.apply(3, SECONDS));
        assertEquals(expected, actual);
    }

    @Test
    public void usePipe() throws Exception {
        TestProbe probe = new TestProbe(system);
        pipe(Futures.successful("ho!"), system.dispatcher()).to(probe.ref());
        probe.expectMsg("ho!");
    }

    @Test
    public void usePipeWithActorSelection() throws Exception {
        TestProbe probe = new TestProbe(system);
        ActorSelection selection = system.actorSelection(probe.ref().path());
        pipe(Futures.successful("hi!"), system.dispatcher()).to(selection);
        probe.expectMsg("hi!");
    }

    @Test
    public void testCSPipeToActorRef() throws Exception {
        TestProbe probe = new TestProbe(system);
        CompletableFuture<String> f = new CompletableFuture<>();
        f.complete("ho!");
        PatternsCS.pipe(f, ec).to(probe.ref());
        probe.expectMsg("ho!");
    }

    @Test
    public void testCSPipeToActorSelection() throws Exception {
        TestProbe probe = new TestProbe(system);
        ActorSelection selection = system.actorSelection(probe.ref().path());
        CompletableFuture<String> f = new CompletableFuture<>();
        f.complete("hi!");
        PatternsCS.pipe(f, ec).to(selection);
        probe.expectMsg("hi!");
    }

    @Test
    public void testRetry() throws Exception {
        final String expected = "hello";

        Future<String> retriedFuture =
                Patterns.retry(
                        () -> Futures.successful(expected),
                        3,
                        Duration.apply(200, "millis"),
                        system.scheduler(), ec);

        String actual = Await.result(retriedFuture, FiniteDuration.apply(3, SECONDS));
        assertEquals(expected, actual);
    }

    @Test
    public void testCSRetry() throws Exception {
        final String expected = "hello";

        Callable<CompletionStage<String>> attempt = () -> CompletableFuture.completedFuture(expected);

        CompletionStage<String> retriedStage =
                PatternsCS.retry(
                        attempt,
                        3,
                        java.time.Duration.ofMillis(200),
                        system.scheduler(), ec);

        final String actual = retriedStage.toCompletableFuture().get(3, SECONDS);
        assertEquals(expected, actual);
    }

    @Test(expected = IllegalStateException.class)
    public void testAfterFailedCallable() throws Exception {
        Callable<Future<String>> failedCallable = () -> Futures.failed(new IllegalStateException("Illegal!"));

        Future<String> delayedFuture = Patterns
                .after(
                        Duration.create(200, "millis"),
                        system.scheduler(),
                        ec,
                        failedCallable);

        Future<String> resultFuture = Futures.firstCompletedOf(Arrays.asList(delayedFuture), ec);
        Await.result(resultFuture, FiniteDuration.apply(3, SECONDS));
    }

    @Test(expected = IllegalStateException.class)
    public void testAfterFailedFuture() throws Exception {
        Future<String> failedFuture = Futures.failed(new IllegalStateException("Illegal!"));

        Future<String> delayedFuture = Patterns
                .after(
                        Duration.create(200, "millis"),
                        system.scheduler(),
                        ec,
                        failedFuture);

        Future<String> resultFuture = Futures.firstCompletedOf(Arrays.asList(delayedFuture), ec);
        Await.result(resultFuture, FiniteDuration.apply(3, SECONDS));
    }

    @Test
    public void testAfterSuccessfulCallable() throws Exception {
        final String expected = "Hello";

        Future<String> delayedFuture = Patterns
                .after(
                        Duration.create(200, "millis"),
                        system.scheduler(),
                        ec,
                        () -> Futures.successful(expected));

        Future<String> resultFuture = Futures.firstCompletedOf(Arrays.asList(delayedFuture), ec);
        final String actual = Await.result(resultFuture, FiniteDuration.apply(3, SECONDS));

        assertEquals(expected, actual);
    }

    @Test
    public void testAfterSuccessfulFuture() throws Exception {
        final String expected = "Hello";

        Future<String> delayedFuture = Patterns
                .after(
                        Duration.create(200, "millis"),
                        system.scheduler(),
                        ec,
                        Futures.successful(expected));

        Future<String> resultFuture = Futures.firstCompletedOf(Arrays.asList(delayedFuture), ec);

        final String actual = Await.result(resultFuture, FiniteDuration.apply(3, SECONDS));
        assertEquals(expected, actual);
    }

    @Test
    public void testAfterFiniteDuration() throws Exception {
        final String expected = "Hello";

        Future<String> delayedFuture = Patterns
                .after(
                        Duration.create(200, "millis"),
                        system.scheduler(),
                        ec,
                        Futures.successful("world"));

        Future<String> immediateFuture = Futures.future(() -> expected, ec);

        Future<String> resultFuture = Futures.firstCompletedOf(Arrays.asList(delayedFuture, immediateFuture), ec);

        final String actual = Await.result(resultFuture, FiniteDuration.apply(3, SECONDS));
        assertEquals(expected, actual);
    }

    @Test(expected = ExecutionException.class)
    public void testCSAfterFailedCallable() throws Exception {
        Callable<CompletionStage<String>> failedCallable = () -> {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Illegal!"));
            return f;
        };

        CompletionStage<String> delayedStage = PatternsCS
                .after(
                        java.time.Duration.ofMillis(200),
                        system.scheduler(),
                        ec,
                        failedCallable);

        delayedStage.toCompletableFuture().get(3, SECONDS);
    }

    @Test(expected = ExecutionException.class)
    public void testCSAfterFailedFuture() throws Exception {
        Callable<CompletionStage<String>> failedFuture = () -> {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Illegal!"));
            return f;
        };

        CompletionStage<String> delayedStage = PatternsCS
                .after(
                        Duration.create(200, "millis"),
                        system.scheduler(),
                        ec,
                        failedFuture);

        String result = delayedStage.toCompletableFuture().get(3, SECONDS);
    }

    @Test
    public void testCSAfterSuccessfulCallable() throws Exception {
        final String expected = "Hello";

        final Callable<CompletionStage<String>> cf = () -> {
            CompletableFuture<String> f = CompletableFuture.completedFuture(expected);
            return f;
        };

        CompletionStage<String> delayedStage = PatternsCS
                .after(
                        java.time.Duration.ofMillis(200),
                        system.scheduler(),
                        ec,
                        cf);

        final String actual = delayedStage.toCompletableFuture().get(3, SECONDS);
        assertEquals(expected, actual);
    }

    @Test
    public void testCSAfterSuccessfulFuture() throws Exception {
        final String expected = "Hello";

        final CompletionStage<String> f = CompletableFuture.completedFuture(expected);

        CompletionStage<String> delayedStage = PatternsCS
                .after(
                        Duration.create(200, "millis"),
                        system.scheduler(),
                        ec,
                        f);

        final String actual = delayedStage.toCompletableFuture().get(3, SECONDS);
        assertEquals(expected, actual);
    }

    @Test
    public void testCSAfterDuration() throws Exception {
        final String expected = "Hello";

        final CompletionStage<String> f = CompletableFuture.completedFuture("world!");

        CompletionStage<String> delayedStage = PatternsCS
                .after(
                        Duration.create(200, "millis"),
                        system.scheduler(),
                        ec,
                        f);

        CompletableFuture<String> immediateStage = CompletableFuture.completedFuture(expected);
        CompletableFuture<Object> resultStage = CompletableFuture.anyOf(delayedStage.toCompletableFuture(), immediateStage);

        final String actual = (String) resultStage.get(3, SECONDS);
        assertEquals(expected, actual);
    }

    @Test
    public void testGracefulStop() throws Exception {
        ActorRef target = system.actorOf(Props.create(StopActor.class));
        Future<Boolean> result = Patterns.gracefulStop(target, FiniteDuration.apply(200, TimeUnit.MILLISECONDS));

        Boolean actual = Await.result(result, FiniteDuration.apply(3, SECONDS));
        assertEquals(true, actual);
    }

    @Test
    public void testCSGracefulStop() throws Exception {
        ActorRef target = system.actorOf(Props.create(StopActor.class));
        CompletionStage<Boolean> result = PatternsCS.gracefulStop(target, java.time.Duration.ofMillis(200));

        Boolean actual = result.toCompletableFuture().get(3, SECONDS);
        assertEquals(true, actual);
    }

}
