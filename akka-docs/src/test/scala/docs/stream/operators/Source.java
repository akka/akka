/**
  * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
  */

//#imports
import akka.stream.javadsl.Source
//#imports

package docs.stream.operators

public class Source {

  def rangeExample() = {
    //#range
    final Source<Integer, NotUsed> source = Source.range(1, 100);
    //#range

    //#range
    final Source<Integer, NotUsed> source = Source.range(1, 100, 5);
    //#range
  }
}