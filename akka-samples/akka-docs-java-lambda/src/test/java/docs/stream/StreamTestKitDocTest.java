/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import java.util.Arrays;
import org.junit.*;

import akka.actor.*;
import akka.testkit.*;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.testkit.*;
import akka.stream.testkit.javadsl.*;

import scala.runtime.BoxedUnit;

public class StreamTestKitDocTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamTestKitDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  @Test
  public void demonstrateTestSourceProbe() {

    //#test-source-probe
    TestSource.<Integer>probe(system)
      .to(Sink.cancelled(), Keep.left())
      .run(mat)
      .expectCancellation();
    //#test-source-probe
  }

  @Test
  public void demonstrateTestSinkProbe() {

    //#test-sink-probe
    Source
      .from(Arrays.asList(1, 2, 3, 4))
      .filter(elem -> elem % 2 == 0)
      .map(elem -> elem * 2)
      .runWith(TestSink.probe(system), mat)
      .request(2)
      .expectNext(4, 8)
      .expectComplete();
    //#test-sink-probe
  }

}
