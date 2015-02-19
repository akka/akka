/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static junit.framework.Assert.assertTrue;

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

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  //#worker-pool
  public static <In, Out> Flow<In, Out> balancer(Flow<In, Out> worker, int workerCount) {
    return Flow.create(b -> {
      UndefinedSource<In> jobsIn = UndefinedSource.create();
      UndefinedSink<Out> resultsOut = UndefinedSink.create();

      boolean waitForAllDownstreams = true;
      Balance<In> balancer = Balance.create(waitForAllDownstreams, OperationAttributes.name("balancer"));
      Merge<Out> merge = Merge.create();

      b.addEdge(jobsIn, balancer);
      b.addEdge(merge, resultsOut);

      for (int i = 0; i < workerCount; i++) {
        b.addEdge(balancer, worker, merge);
      }

      return new Pair<>(jobsIn, resultsOut);
    });
  }
  //#worker-pool

  @Test
  public void workForVersion1() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        Source<Message> data =
          Source
            .from(Arrays.asList("1", "2", "3", "4", "5"))
            .map(t -> new Message(t));

        Flow<Message, Message> worker = Flow.of(Message.class).map(m -> new Message(m.msg + " done"));

        //#worker-pool

        Flow<Message, Message> balancer = balancer(worker, 3);
        Source<Message> processedJobs = data.via(balancer);
        //#worker-pool

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

