/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util;

import org.junit.Test;
import scala.concurrent.duration.Duration;

import static junit.framework.TestCase.assertEquals;

public class ByteStringTest {

  @Test
  public void testCreation() {
    final ByteString s1 = ByteString.fromString("");
    final ByteString s2 = ByteString.fromInts(1, 2, 3);
  }

  @Test
  public void testBuilderCreation() {
    final ByteStringBuilder sb = ByteString.createBuilder();
    sb.append(ByteString.fromString("Hello"));
    sb.append(ByteString.fromString("World"));
    assertEquals(ByteString.fromString("Hello World"), sb.result());
  }

}
