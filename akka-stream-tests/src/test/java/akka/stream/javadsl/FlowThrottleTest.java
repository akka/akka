package akka.stream.javadsl;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.japi.Pair;
import akka.japi.function.Function;
import akka.stream.*;
import akka.testkit.JavaTestKit;
import org.junit.ClassRule;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import akka.testkit.AkkaSpec;
import akka.testkit.AkkaJUnitActorSystemResource;

import static org.junit.Assert.assertEquals;

public class FlowThrottleTest extends StreamTest {
    public FlowThrottleTest() {
        super(actorSystemResource);
    }

    @ClassRule
    public static AkkaJUnitActorSystemResource actorSystemResource =
            new AkkaJUnitActorSystemResource("ThrottleTest", AkkaSpec.testConf());

    @Test
    public void mustWorksForTwoStreams() throws Exception {
        final Flow<Integer, Integer, NotUsed> sharedThrottle =
            Flow.of(Integer.class)
              .throttle(1, FiniteDuration.create(1, TimeUnit.DAYS), 1, ThrottleMode.enforcing());

        CompletionStage<List<Integer>> result1 =
          Source.single(1).via(sharedThrottle).via(sharedThrottle).runWith(Sink.seq(), materializer);

        // If there is accidental shared state then we would not be able to pass through the single element
        assertEquals(result1.toCompletableFuture().get(3, TimeUnit.SECONDS), Collections.singletonList(1));

        // It works with a new stream, too
        CompletionStage<List<Integer>> result2 =
          Source.single(1).via(sharedThrottle).via(sharedThrottle).runWith(Sink.seq(), materializer);

        assertEquals(result2.toCompletableFuture().get(3, TimeUnit.SECONDS), Collections.singletonList(1));
    }

}
