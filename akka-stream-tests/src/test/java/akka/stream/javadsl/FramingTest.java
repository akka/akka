/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl;

import akka.NotUsed;
import akka.stream.StreamTest;
import akka.testkit.AkkaSpec;
import akka.util.ByteString;
import org.junit.ClassRule;
import org.junit.Test;
import akka.testkit.AkkaJUnitActorSystemResource;

public class FramingTest extends StreamTest {
  public FramingTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("FramingTest", AkkaSpec.testConf());

  @Test
  public void mustBeAbleToUseFraming() throws Exception {
    final Source<ByteString, NotUsed> in = Source.single(ByteString.fromString("1,3,4,5"));
    in.via(Framing.delimiter(ByteString.fromString(","), Integer.MAX_VALUE, FramingTruncation.ALLOW))
      .runWith(Sink.ignore(), materializer);
  }
  
}
