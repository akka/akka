/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream;

import scala.runtime.BoxedUnit;

import static org.junit.Assert.assertEquals;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import akka.actor.ActorSystem;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.japi.*;
import akka.testkit.JavaTestKit;

public class FlowParallelismDocTest {

  private static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FlowDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  static class ScoopOfBatter {}
  static class HalfCookedPancake {}
  static class Pancake {}

  //#pipelining
    Flow<ScoopOfBatter, HalfCookedPancake, BoxedUnit> fryingPan1 =
      Flow.of(ScoopOfBatter.class).map(batter -> new HalfCookedPancake());

    Flow<HalfCookedPancake, Pancake, BoxedUnit> fryingPan2 =
      Flow.of(HalfCookedPancake.class).map(halfCooked -> new Pancake());
  //#pipelining

  @Test
  public void demonstratePipelining() {
    //#pipelining

    // With the two frying pans we can fully cook pancakes
    Flow<ScoopOfBatter, Pancake, BoxedUnit> pancakeChef = fryingPan1.via(fryingPan2);
    //#pipelining
  }

  @Test
  public void demonstrateParallelism() {
    //#parallelism
    Flow<ScoopOfBatter, Pancake, BoxedUnit> fryingPan =
      Flow.of(ScoopOfBatter.class).map(batter -> new Pancake());

    Flow<ScoopOfBatter, Pancake, BoxedUnit> pancakeChef =
      Flow.factory().create(b -> {
        final UniformFanInShape<Pancake, Pancake> mergePancakes =
          b.graph(Merge.create(2));
        final UniformFanOutShape<ScoopOfBatter, ScoopOfBatter> dispatchBatter =
          b.graph(Balance.create(2));

        // Using two frying pans in parallel, both fully cooking a pancake from the batter.
        // We always put the next scoop of batter to the first frying pan that becomes available.
        b.from(dispatchBatter.out(0)).via(fryingPan).to(mergePancakes.in(0));
        // Notice that we used the "fryingPan" flow without importing it via builder.add().
        // Flows used this way are auto-imported, which in this case means that the two
        // uses of "fryingPan" mean actually different stages in the graph.
        b.from(dispatchBatter.out(1)).via(fryingPan).to(mergePancakes.in(1));

        return new Pair(dispatchBatter.in(), mergePancakes.out());
      });
    //#parallelism
  }

  @Test
  public void parallelPipeline() {
    //#parallel-pipeline
    Flow<ScoopOfBatter, Pancake, BoxedUnit> pancakeChef =
      Flow.factory().create(b -> {
        final UniformFanInShape<Pancake, Pancake> mergePancakes =
          b.graph(Merge.create(2));
        final UniformFanOutShape<ScoopOfBatter, ScoopOfBatter> dispatchBatter =
          b.graph(Balance.create(2));

        // Using two pipelines, having two frying pans each, in total using
        // four frying pans
        b.from(dispatchBatter.out(0))
          .via(fryingPan1)
          .via(fryingPan2)
          .to(mergePancakes.in(0));

        b.from(dispatchBatter.out(1))
          .via(fryingPan1)
          .via(fryingPan2)
          .to(mergePancakes.in(1));

        return new Pair(dispatchBatter.in(), mergePancakes.out());
      });
    //#parallel-pipeline
  }

  @Test
  public void pipelinedParallel() {
    //#pipelined-parallel
    Flow<ScoopOfBatter, HalfCookedPancake, BoxedUnit> pancakeChefs1 =
      Flow.factory().create(b -> {
        final UniformFanInShape<HalfCookedPancake, HalfCookedPancake> mergeHalfCooked =
          b.graph(Merge.create(2));
        final UniformFanOutShape<ScoopOfBatter, ScoopOfBatter> dispatchBatter =
          b.graph(Balance.create(2));

        // Two chefs work with one frying pan for each, half-frying the pancakes then putting
        // them into a common pool
        b.from(dispatchBatter.out(0)).via(fryingPan1).to(mergeHalfCooked.in(0));
        b.from(dispatchBatter.out(1)).via(fryingPan1).to(mergeHalfCooked.in(1));

        return new Pair(dispatchBatter.in(), mergeHalfCooked.out());
      });

    Flow<HalfCookedPancake, Pancake, BoxedUnit> pancakeChefs2 =
      Flow.factory().create(b -> {
        final UniformFanInShape<Pancake, Pancake> mergePancakes =
          b.graph(Merge.create(2));
        final UniformFanOutShape<HalfCookedPancake, HalfCookedPancake> dispatchHalfCooked =
          b.graph(Balance.create(2));

        // Two chefs work with one frying pan for each, finishing the pancakes then putting
        // them into a common pool
        b.from(dispatchHalfCooked.out(0)).via(fryingPan2).to(mergePancakes.in(0));
        b.from(dispatchHalfCooked.out(1)).via(fryingPan2).to(mergePancakes.in(1));

        return new Pair(dispatchHalfCooked.in(), mergePancakes.out());
      });

    Flow<ScoopOfBatter, Pancake, BoxedUnit> kitchen =
        pancakeChefs1.via(pancakeChefs2);
    //#pipelined-parallel
  }
}