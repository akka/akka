/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.stream.javadsl;

import org.reactivestreams.Publisher;

/** TODO java doc */
public interface Jdk9Sink {

  // this version of Jdk9FlowOps will only be loaded in JDK8
  // and since we can't provide JDK9 types there - this class is empty,
  // and exists only for the sake of compilation of akka.stream.javadsl.Flow
  // which extends it, and gains "zero new methods" from it, in JDK8.
  
  // new methods are present in the src/main/java-jdk9 version of this class
  // and are emitted using Multi-Release JARs to JDK9 end-users for their consumption.
  
}
