package docs.stream;

import akka.actor.ActorSystem;
//#imports
import akka.stream.*;
import akka.stream.javadsl.Source;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
//#imports
import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import static org.junit.Assert.assertEquals;

public class GraphStageDocTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FlowGraphDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);


  //#simple-source
  public class NumbersSource extends GraphStage<SourceShape<Integer>> {
    // Define the (sole) output port of this stage
    public final Outlet<Integer> out = Outlet.create("NumbersSource.out");

    // Define the shape of this stage, which is SourceShape with the port we defined above
    private final SourceShape<Integer> shape = SourceShape.of(out);
    @Override
    public SourceShape<Integer> shape() {
      return shape;
    }

    // This is where the actual (possibly stateful) logic is created
    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape()) {
        // All state MUST be inside the GraphStageLogic,
        // never inside the enclosing GraphStage.
        // This state is safe to access and modify from all the
        // callbacks that are provided by GraphStageLogic and the
        // registered handlers.
        private int counter = 1;

        {
          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() {
              push(out, counter);
              counter += 1;
            }
          });
        }

      };
    }

  }
  //#simple-source


  @Test
  public void demonstrateCustomSourceUsage() throws Exception {
    //#simple-source-usage
    // A GraphStage is a proper Graph, just like what FlowGraph.create would return
    Graph<SourceShape<Integer>, BoxedUnit> sourceGraph = new NumbersSource();

    // Create a Source from the Graph to access the DSL
    Source<Integer, BoxedUnit> mySource = Source.fromGraph(sourceGraph);

    // Returns 55
    Future<Integer> result1 = mySource.take(10).runFold(0, (sum, next) -> sum + next, mat);

    // The source is reusable. This returns 5050
    Future<Integer> result2 = mySource.take(100).runFold(0, (sum, next) -> sum + next, mat);
    //#simple-source-usage

    assertEquals(Await.result(result1, Duration.create(3, "seconds")), (Integer) 55);
    assertEquals(Await.result(result2, Duration.create(3, "seconds")), (Integer) 5050);
  }

}
