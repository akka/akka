/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

import akka.stream.javadsl.Flow;

//#log
import akka.stream.Attributes;
import akka.stream.javadsl.Source;

import java.util.Arrays;

//#log

class SourceOrFlow {

  void logExample() {
    Flow.of(String.class)
        //#log
        .log("myStream")
        .addAttributes(Attributes.createLogLevels(
            Attributes.logLevelOff(), // onElement
            Attributes.logLevelError(), // onFailure
            Attributes.logLevelInfo())) // onFinish
    //#log
    ;
  }

  void conflateExample() {
    //#conflate
    Source.from(Arrays.asList(1, 10, 100))
        .conflate((Integer acc, Integer el) -> acc + el);
    //#conflate
  }

  static //#conflateWithSeed
      class NewType {

    private final Integer el;

    public NewType(Integer el) {
      this.el = el;
    }

    public NewType sum(NewType other) {
      return new NewType(this.el + other.el);
    }
  }
  //#conflateWithSeed

  void conflateWithSeedExample() {
    //#conflateWithSeed

    Source.from(Arrays.asList(1, 10, 100))
        .conflateWithSeed(NewType::new, (NewType acc, Integer el) -> acc.sum(new NewType(el)));
    //#conflateWithSeed
  }
}
