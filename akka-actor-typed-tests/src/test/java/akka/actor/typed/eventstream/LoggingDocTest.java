/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.eventstream;

import static akka.actor.typed.javadsl.Adapter.spawn;
import static akka.actor.typed.javadsl.Adapter.toClassic;

import akka.actor.Actor;
import akka.actor.AllDeadLetters;
import akka.actor.SuppressedDeadLetter;
import akka.actor.Terminated;
import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.SpawnProtocol;
import akka.actor.typed.SpawnProtocol.Spawn;
import akka.actor.typed.eventstream.EventStream.Publish;
import akka.actor.typed.javadsl.Adapter;
import akka.actor.typed.javadsl.AskPattern;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import org.junit.Assert;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;
// #imports-deadletter
import akka.actor.DeadLetter;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.eventstream.EventStream.Subscribe;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
// #imports-deadletter

public class LoggingDocTest extends JUnitSuite {

    @Test
    public void subscribeToDeadLetters() {
        ActorSystem<SpawnProtocol.Command> system = ActorSystem.create(SpawnProtocol.create(),
            "DeadLettersSystem");
        // #subscribe-deadletter
        CompletionStage<ActorRef<DeadLetter>> spawnActorFuture = AskPattern.ask(system,
            r -> new Spawn<>(DeadLetterActor.create(), "DeadLetters", Props.empty(), r),
            Duration.ofSeconds(3), system.scheduler());
        ActorRef<DeadLetter> deadLetters = spawnActorFuture.toCompletableFuture().join();
        system.eventStream().tell(new Subscribe<>(DeadLetter.class, deadLetters));
        // #subscribe-deadletter
        ActorTestKit.shutdown(system);
    }

    public
    // #deadletter-actor
    static class DeadLetterActor extends AbstractBehavior<DeadLetter> {

        final Logger log = getContext().getLog();

        public static Behavior<DeadLetter> create() {
            return Behaviors.setup(DeadLetterActor::new);
        }

        public DeadLetterActor(ActorContext<DeadLetter> context) {
            super(context);
            ActorRef<DeadLetter> messageAdapter = context.messageAdapter(
                DeadLetter.class,
                d -> d
            );
            // subscribe DeadLetter at start up.
            context.getSystem().eventStream()
                .tell(new Subscribe<>(DeadLetter.class, messageAdapter));
        }

        @Override
        public Receive<DeadLetter> createReceive() {
            return newReceiveBuilder().onMessage(DeadLetter.class, msg -> {
                log.info("receive dead letter: {} from <{}> to <{}>", msg, msg.sender(),
                    msg.recipient());
                return Behaviors.same();
            }).build();
        }
    }
    // #deadletter-actor

    // #superclass-subscription-eventstream
    interface AllKindsOfMusic {

    }

    class Jazz implements AllKindsOfMusic {

        public final String artist;

        public Jazz(String artist) {
            this.artist = artist;
        }
    }

    class Electronic implements AllKindsOfMusic {

        public final String artist;

        public Electronic(String artist) {
            this.artist = artist;
        }
    }

    static class Listener extends AbstractBehavior<AllKindsOfMusic> {

        final Logger log = getContext().getLog();

        public static Behavior<AllKindsOfMusic> create() {
            return Behaviors.setup(Listener::new);
        }

        public Listener(ActorContext<AllKindsOfMusic> context) {
            super(context);
        }


        @Override
        public Receive<AllKindsOfMusic> createReceive() {
            return newReceiveBuilder()
                .onMessage(Jazz.class, msg -> {
                    log.info("{} is listening to Jazz: {}", getContext().getSelf().path().name(),
                        msg);
                    return Behaviors.same();
                })
                .onMessage(Electronic.class, msg -> {
                    log.info("{} is listening to Electronic: {}",
                        getContext().getSelf().path().name(), msg);
                    return Behaviors.same();
                }).build();
        }
    }
    // #superclass-subscription-eventstream

    @Test
    public void subscribeBySubclassification() {
        // #superclass-subscription-eventstream
        ActorSystem<SpawnProtocol.Command> system = ActorSystem.create(SpawnProtocol.create(),
            "Subclassification");
        final Duration timeout = Duration.ofSeconds(3);

        CompletionStage<ActorRef<Jazz>> jazzListener = AskPattern.ask(
            system,
            replyTo -> new Spawn(Listener.create(), "jazzListener", Props.empty(), replyTo),
            timeout,
            system.scheduler()
        );

        CompletionStage<ActorRef<AllKindsOfMusic>> musicListener = AskPattern.ask(
            system,
            replyTo -> new Spawn(Listener.create(), "musicListener", Props.empty(), replyTo),
            timeout,
            system.scheduler()
        );

        ActorRef<Jazz> jazzListenerActorRef = jazzListener.toCompletableFuture().join();
        ActorRef<AllKindsOfMusic> musicListenerActorRef = musicListener.toCompletableFuture()
            .join();

        system.eventStream().tell(new Subscribe<>(Jazz.class, jazzListenerActorRef));
        system.eventStream().tell(new Subscribe<>(AllKindsOfMusic.class, musicListenerActorRef));
        // only musicListener gets this message, since it listens to *all* kinds of music:
        system.eventStream().tell(new Publish<>(new Electronic("Parov Stelar")));

        // jazzListener and musicListener will be notified about Jazz:
        system.eventStream().tell(new Publish<>(new Jazz("Sonny Rollins")));

        // #superclass-subscription-eventstream
        ActorTestKit.shutdown(system);
    }

    @Test
    public void subscribeToSuppressedDeadLetters() {
        ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "SuppressedDeadLetter");
        TestProbe<SuppressedDeadLetter> probe = TestProbe.create(system);
        ActorRef<SuppressedDeadLetter> listener = probe.ref();
        akka.actor.ActorRef mockRef = Adapter.toClassic(listener);
        // #suppressed-deadletters
        system.eventStream().tell(new Subscribe<>(SuppressedDeadLetter.class, listener));
        // #suppressed-deadletters
        Terminated suppression = Terminated.apply(mockRef, false, false);
        SuppressedDeadLetter deadLetter = SuppressedDeadLetter.apply(suppression, mockRef, mockRef);
        system.eventStream().tell(new Publish<>(deadLetter));

        SuppressedDeadLetter suppressedDeadLetter = probe.expectMessageClass(
            SuppressedDeadLetter.class);
        Assert.assertNotNull(suppressedDeadLetter);
        Assert.assertEquals(deadLetter, suppressedDeadLetter);

        ActorTestKit.shutdown(system);
    }

    @Test
    public void subscribeToAllDeadLetters() {
        ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "AllDeadLetters");
        TestProbe<AllDeadLetters> probe = TestProbe.create(system);
        ActorRef<AllDeadLetters> listener = probe.ref();
        akka.actor.ActorRef mockRef = Adapter.toClassic(listener);
        // #all-deadletters
        system.eventStream().tell(new Subscribe<>(AllDeadLetters.class, listener));
        // #all-deadletters

        Terminated suppression = Terminated.apply(Actor.noSender(), false, false);
        SuppressedDeadLetter suppressedDeadLetter = SuppressedDeadLetter.apply(suppression,
            mockRef,
            mockRef);
        system.eventStream().tell(new Publish<>(suppressedDeadLetter));
        DeadLetter deadLetter = DeadLetter.apply("deadLetter", mockRef, mockRef);
        system.eventStream().tell(new Publish<>(deadLetter));

        // both of the following messages will be received by the subscription actor
        SuppressedDeadLetter receiveSuppressed = probe.expectMessageClass(
            SuppressedDeadLetter.class);
        Assert.assertNotNull(receiveSuppressed);
        Assert.assertEquals(suppressedDeadLetter, receiveSuppressed);
        DeadLetter receiveDeadLetter = probe.expectMessageClass(DeadLetter.class);
        Assert.assertNotNull(receiveDeadLetter);
        Assert.assertEquals(deadLetter, receiveDeadLetter);

        ActorTestKit.shutdown(system);
    }
}
