package akka.typed.javadsl;

import java.util.concurrent.CompletionStage;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import akka.Done;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.Duration;
import akka.util.Timeout;
import org.junit.Test;

import akka.typed.*;
import static akka.typed.javadsl.Actor.*;
import static akka.typed.javadsl.AskPattern.*;

public class MonitoringTest extends JUnitSuite {

    static interface Message {}
    static final class RunTest<T> implements Message {
        private final ActorRef<T> replyTo;
        public RunTest(ActorRef<T> replyTo) {
            this.replyTo = replyTo;
        }
    }
    static final class Stop {}
    static final class CustomTerminationMessage implements Message {}

    // final FiniteDuration fiveSeconds = FiniteDuration.create(5, TimeUnit.SECONDS);
    final Timeout timeout = new Timeout(Duration.create(5, TimeUnit.SECONDS));

    final Behavior<Stop> exitingActor = immutable((ctx, msg) -> {
        System.out.println("Stopping!");
        return stopped();
    });

    private Behavior<RunTest<Done>> waitingForTermination(ActorRef<Done> replyWhenTerminated) {
        return immutable(
            (ctx, msg) -> unhandled(),
            (ctx, sig) -> {
                if (sig instanceof Terminated) {
                    replyWhenTerminated.tell(Done.getInstance());
                }
                return same();
            }
        );
    }

    private Behavior<Message> waitingForMessage(ActorRef<Done> replyWhenReceived) {
        return immutable(
            (ctx, msg) -> {
                if (msg instanceof CustomTerminationMessage) {
                    replyWhenReceived.tell(Done.getInstance());
                    return same();
                } else {
                    return unhandled();
                }
            }
        );
    }

    @Test
    public void shouldWatchTerminatingActor() throws Exception {
        Behavior<RunTest<Done>> root = immutable((ctx, msg) -> {
            ActorRef<Stop> watched = ctx.spawn(exitingActor, "exitingActor");
            ctx.watch(watched);
            watched.tell(new Stop());
            return waitingForTermination(msg.replyTo);
        });
        ActorSystem<RunTest<Done>> system = ActorSystem$.MODULE$.create("sysname", root, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // Not sure why this does not compile without an explicit cast?
        // system.tell(new RunTest());
        CompletionStage<Done> result = AskPattern.ask((ActorRef<RunTest<Done>>)system, (ActorRef<Done> ref) -> new RunTest<Done>(ref), timeout, system.scheduler());
        result.toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    @Test
    public void shouldWatchWithCustomMessage() throws Exception {
        Behavior<Message> root = immutable((ctx, msg) -> {
            if (msg instanceof RunTest) {
                ActorRef<Stop> watched = ctx.spawn(exitingActor, "exitingActor");
                ctx.watchWith(watched, new CustomTerminationMessage());
                watched.tell(new Stop());
                return waitingForMessage(((RunTest) msg).replyTo);
            } else {
                return unhandled();
            }
        });
        ActorSystem<Message> system = ActorSystem$.MODULE$.create("sysname", root, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // Not sure why this does not compile without an explicit cast?
        // system.tell(new RunTest());
        CompletionStage<Done> result = AskPattern.ask((ActorRef<Message>)system, (ActorRef<Done> ref) -> new RunTest<Done>(ref), timeout, system.scheduler());
        result.toCompletableFuture().get(3, TimeUnit.SECONDS);
    }
}