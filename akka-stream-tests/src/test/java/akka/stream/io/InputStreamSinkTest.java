/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.io;

import akka.stream.StreamTest;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.StreamConverters;
import akka.stream.testkit.Utils;
import akka.util.ByteString;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class InputStreamSinkTest extends StreamTest {
  public InputStreamSinkTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("InputStreamSink", Utils.UnboundedMailboxConfig());

  @Test
  public void mustReadEventViaInputStream() throws Exception {
    final Duration timeout = Duration.ofMillis(300);

    final Sink<ByteString, InputStream> sink = StreamConverters.asInputStream(timeout);
    final List<ByteString> list = Collections.singletonList(ByteString.fromString("a"));
    final InputStream stream = Source.from(list).runWith(sink, system);

    byte[] a = new byte[1];
    stream.read(a);
    assertTrue(Arrays.equals("a".getBytes(), a));
  }
}
