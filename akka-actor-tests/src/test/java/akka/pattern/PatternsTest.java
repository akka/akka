/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern;

import akka.actor.*;
import akka.dispatch.Futures;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.CompletionStage;

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

    @ClassRule
    public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("JavaAPI",
            AkkaSpec.testConf());

    private final ActorSystem system = actorSystemResource.getSystem();


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
}
