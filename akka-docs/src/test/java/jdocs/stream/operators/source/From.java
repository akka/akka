/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.source;

import akka.actor.ActorSystem;
import akka.stream.javadsl.Source;

import java.util.Arrays;

public class From {

  private ActorSystem system = null;

  void fromIteratorSample() {
    // #from-iterator
    Source.fromIterator(() -> Arrays.asList(1, 2, 3).iterator())
        .runForeach(System.out::println, system);
    // could print
    // 1
    // 2
    // 3
    // #from-iterator
  }
}
