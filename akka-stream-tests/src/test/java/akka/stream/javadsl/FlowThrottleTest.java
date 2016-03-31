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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import akka.testkit.AkkaSpec;

public class FlowThrottleTest extends StreamTest {
    public FlowThrottleTest() {
        super(actorSystemResource);
    }

    @ClassRule
    public static AkkaJUnitActorSystemResource actorSystemResource =
            new AkkaJUnitActorSystemResource("ThrottleTest", AkkaSpec.testConf());

    @Test
    public void mustWorksForTwoStreams() throws Exception {
        final JavaTestKit probe1 = new JavaTestKit(system);
        final JavaTestKit probe2 = new JavaTestKit(system);

        final Function<Throwable, Supervision.Directive> decider = exc -> {
            return Supervision.restart();
        };

        final Materializer mat1 = ActorMaterializer.create(ActorMaterializerSettings.create(system).withSupervisionStrategy(decider), system);
        final Materializer mat2 = ActorMaterializer.create(ActorMaterializerSettings.create(system).withSupervisionStrategy(decider), system);

        final String name1 = "Pipe 1: ";
        final String name2 = "Pipe 2: ";

        Source<Object, NotUsed>  pipe1 = createPipeLine(name1);
        final CompletionStage<Done> future1 = pipe1.runWith(Sink.foreach(elem ->
            probe1.getRef().tell(elem, ActorRef.noSender())), mat1);

        Source<Object, NotUsed>  pipe2 = createPipeLine(name2);
        final CompletionStage<Done> future2 = pipe2.runWith(Sink.foreach(elem ->
            probe2.getRef().tell(elem, ActorRef.noSender())), mat2);

        final FiniteDuration duration = FiniteDuration.apply(200, TimeUnit.MILLISECONDS);

        probe1.expectNoMsg(duration);
        probe2.expectNoMsg(duration);

        probe2.expectMsgEquals(name2 + "0");
        probe1.expectMsgEquals(name1 + "0");

        probe1.expectNoMsg(duration);
        probe2.expectNoMsg(duration);

        probe1.expectMsgEquals(name1 + "00");
        probe2.expectMsgEquals(name2 + "00");

        future1.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
        future2.toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
    }


    private Source<Object, NotUsed>  createPipeLine(String name) throws Exception{
        Source<Collection<Object>, NotUsed> source = Source.<Object, Collection<Object>>unfoldAsync(name, p -> {
            final CompletableFuture<Optional<Pair<Object, Collection<Object>>>> promise = new CompletableFuture<>();
            if(p.toString().length() < name.length() + 2) {
                Object str = p.toString() + "0";
                ArrayList list = new ArrayList();
                list.add(str);
                promise.complete(Optional.of(new Pair<>(str, list)));
            }
            else
                promise.complete(Optional.empty());
            return promise;
        });

        Flow<Collection<Object>,Collection<Object>,NotUsed> throttle =
                Flow.<Collection<Object>>create().throttle(1, FiniteDuration.apply(400, "millis"),1,
                        akka.stream.ThrottleMode.shaping());

        Source<Object, NotUsed> pipeline = source.via(throttle).mapConcat(t -> t).buffer(30, OverflowStrategy.backpressure());
        return pipeline;
    }
}
