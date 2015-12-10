/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs;

import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

public class MigrationsJava {

  // This is compile-only code, no need for actually running anything.
  public static ActorMaterializer mat = null;

  {
    //#group-flatten
    Flow.<Integer> create()
      .groupBy(2, i -> i % 2) // the first parameter sets max number of substreams
      .map(i -> i + 3)
      .concatSubstreams();
    //#group-flatten

    final int MaxDistinctWords = 1000;
    //#group-fold
    Flow.<String> create()
      .groupBy(MaxDistinctWords, i -> i)
      .fold(new Pair<>("", 0), (pair, word) -> new Pair<>(word, pair.second() + 1))
      .mergeSubstreams();
    //#group-fold
  }
  
}
