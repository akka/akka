package docs.stream

import akka.stream.scaladsl.{ FlowGraph, Merge, Balance, Source, Flow }
import akka.stream.testkit.AkkaSpec

class FlowParallelismDocSpec extends AkkaSpec {

  import FlowGraph.Implicits._

  case class ScoopOfBatter()
  case class HalfCookedPancake()
  case class Pancake()

  //format: OFF
  //#pipelining
    // Takes a scoop of batter and creates a pancake with one side cooked
    val fryingPan1: Flow[ScoopOfBatter, HalfCookedPancake, Unit] =
      Flow[ScoopOfBatter].map { batter => HalfCookedPancake() }

    // Finishes a half-cooked pancake
    val fryingPan2: Flow[HalfCookedPancake, Pancake, Unit] =
      Flow[HalfCookedPancake].map { halfCooked => Pancake() }
  //#pipelining
  //format: ON

  "Demonstrate pipelining" in {
    //#pipelining

    // With the two frying pans we can fully cook pancakes
    val pancakeChef: Flow[ScoopOfBatter, Pancake, Unit] =
      Flow[ScoopOfBatter].via(fryingPan1).via(fryingPan2)
    //#pipelining
  }

  "Demonstrate parallel processing" in {
    //#parallelism
    val fryingPan: Flow[ScoopOfBatter, Pancake, Unit] =
      Flow[ScoopOfBatter].map { batter => Pancake() }

    val pancakeChef: Flow[ScoopOfBatter, Pancake, Unit] = Flow() { implicit builder =>
      val dispatchBatter = builder.add(Balance[ScoopOfBatter](2))
      val mergePancakes = builder.add(Merge[Pancake](2))

      // Using two frying pans in parallel, both fully cooking a pancake from the batter.
      // We always put the next scoop of batter to the first frying pan that becomes available.
      dispatchBatter.out(0) ~> fryingPan ~> mergePancakes.in(0)
      // Notice that we used the "fryingPan" flow without importing it via builder.add().
      // Flows used this way are auto-imported, which in this case means that the two
      // uses of "fryingPan" mean actually different stages in the graph.
      dispatchBatter.out(1) ~> fryingPan ~> mergePancakes.in(1)

      (dispatchBatter.in, mergePancakes.out)
    }

    //#parallelism
  }

  "Demonstrate parallelized pipelines" in {
    //#parallel-pipeline
    val pancakeChef: Flow[ScoopOfBatter, Pancake, Unit] = Flow() { implicit builder =>

      val dispatchBatter = builder.add(Balance[ScoopOfBatter](2))
      val mergePancakes = builder.add(Merge[Pancake](2))

      // Using two pipelines, having two frying pans each, in total using
      // four frying pans
      dispatchBatter.out(0) ~> fryingPan1 ~> fryingPan2 ~> mergePancakes.in(0)
      dispatchBatter.out(1) ~> fryingPan1 ~> fryingPan2 ~> mergePancakes.in(1)

      (dispatchBatter.in, mergePancakes.out)
    }
    //#parallel-pipeline
  }

  "Demonstrate pipelined parallel processing" in {
    //#pipelined-parallel
    val pancakeChefs1: Flow[ScoopOfBatter, HalfCookedPancake, Unit] = Flow() { implicit builder =>
      val dispatchBatter = builder.add(Balance[ScoopOfBatter](2))
      val mergeHalfPancakes = builder.add(Merge[HalfCookedPancake](2))

      // Two chefs work with one frying pan for each, half-frying the pancakes then putting
      // them into a common pool
      dispatchBatter.out(0) ~> fryingPan1 ~> mergeHalfPancakes.in(0)
      dispatchBatter.out(1) ~> fryingPan1 ~> mergeHalfPancakes.in(1)

      (dispatchBatter.in, mergeHalfPancakes.out)
    }

    val pancakeChefs2: Flow[HalfCookedPancake, Pancake, Unit] = Flow() { implicit builder =>
      val dispatchHalfPancakes = builder.add(Balance[HalfCookedPancake](2))
      val mergePancakes = builder.add(Merge[Pancake](2))

      // Two chefs work with one frying pan for each, finishing the pancakes then putting
      // them into a common pool
      dispatchHalfPancakes.out(0) ~> fryingPan2 ~> mergePancakes.in(0)
      dispatchHalfPancakes.out(1) ~> fryingPan2 ~> mergePancakes.in(1)

      (dispatchHalfPancakes.in, mergePancakes.out)
    }

    val kitchen: Flow[ScoopOfBatter, Pancake, Unit] = pancakeChefs1.via(pancakeChefs2)
    //#pipelined-parallel

  }

}
