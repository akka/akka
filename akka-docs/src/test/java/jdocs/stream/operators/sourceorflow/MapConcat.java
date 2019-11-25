package jdocs.stream.operators.sourceorflow;

import akka.actor.ActorSystem;
import akka.stream.javadsl.Source;

import java.util.Arrays;

public class MapConcat {
   private static ActorSystem system = null;

   //#map-concat
   Iterable<Integer> duplicate(int i) {
      return Arrays.asList(i, i);
   }

   //#map-concat

   void example() {
      //#map-concat
      Source.from(Arrays.asList(1,2,3))
         .mapConcat(i -> duplicate(i))
         .runForeach(System.out::println, system);
      //#map-concat
   }
}
