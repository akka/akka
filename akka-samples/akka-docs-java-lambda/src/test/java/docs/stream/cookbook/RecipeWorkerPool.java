/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.UniformFanInShape;
import akka.stream.UniformFanOutShape;
import akka.stream.FlowShape;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

public class RecipeWorkerPool extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeWorkerPool");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  //#worker-pool
  public static <In, Out> Flow<In, Out, BoxedUnit> balancer(
      Flow<In, Out, BoxedUnit> worker, int workerCount) {
    return Flow.fromGraph(FlowGraph.create(b -> {
        boolean waitForAllDownstreams = true;
        final UniformFanOutShape<In, In> balance =
                b.add(Balance.<In>create(workerCount, waitForAllDownstreams));
        final UniformFanInShape<Out, Out> merge =
                b.add(Merge.<Out>create(workerCount));

        for (int i = 0; i < workerCount; i++) {
            b.from(balance.out(i)).via(b.add(worker)).toInlet(merge.in(i));
        }

        return FlowShape.of(balance.in(), merge.out());
    }));
  }
  //#worker-pool

  @Test
  public void workForVersion1() throws Exception {
    new JavaTestKit(system) {
      {
        Source<Message, BoxedUnit> data =
          Source
            .from(Arrays.asList("1", "2", "3", "4", "5"))
            .map(t -> new Message(t));

        Flow<Message, Message, BoxedUnit> worker = Flow.of(Message.class).map(m -> new Message(m.msg + " done"));

        //#worker-pool2
        Flow<Message, Message, BoxedUnit> balancer = balancer(worker, 3);
        Source<Message, BoxedUnit> processedJobs = data.via(balancer);
        //#worker-pool2

        FiniteDuration timeout = FiniteDuration.create(200, TimeUnit.MILLISECONDS);
        Future<List<String>> future = processedJobs.map(m -> m.msg).grouped(10).runWith(Sink.head(), mat);
        List<String> got = Await.result(future, timeout);
        assertTrue(got.contains("1 done"));
        assertTrue(got.contains("2 done"));
        assertTrue(got.contains("3 done"));
        assertTrue(got.contains("4 done"));
        assertTrue(got.contains("5 done"));
      }
    };
  }

}
