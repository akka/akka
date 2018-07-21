/**
  * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
  */
package docs.stream.operators;

//#imports
import akka.NotUsed;
import akka.stream.javadsl.Source;

//#imports

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;

public class Source {

  public static void main(String[] args) {
    rangeExample();
  }

  static void rangeExample() {

    final ActorSystem system = ActorSystem.create("Source");
    final Materializer materializer = ActorMaterializer.create(system);

    //#range
    Source<Integer, NotUsed> source = Source.range(1, 100);

    //#range

    //#range
    Source<Integer, NotUsed> sourceStepFive = Source.range(1, 100, 5);

    //#range

    //#range
    Source<Integer, NotUsed> sourceStepNegative = Source.range(100, 1, -1);
    //#range

    //#run-range
    source.runForeach(i -> System.out.println(i), materializer);
    //#run-range

}