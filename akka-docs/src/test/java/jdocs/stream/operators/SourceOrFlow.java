/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

import akka.stream.javadsl.Flow;

//#import
import akka.stream.javadsl.Source;
import java.util.Arrays;

//#import
//#log
import akka.stream.Attributes;

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

  void zipWithIndexExample() {
    //#zip-with-index
    Source.from(Arrays.asList(7, 8, 9)).zipWithIndex();
    //#zip-with-index
  }
}
