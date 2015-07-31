/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import akka.stream.Attributes;

public class AttributesTest {
  
  final Attributes attributes =
      Attributes.name("a")
      .and(Attributes.name("b"))
      .and(Attributes.inputBuffer(1, 2));

  @Test
  public void mustGetAttributesByClass() {
    assertEquals(
      Arrays.asList(new Attributes.Name("a"), new Attributes.Name("b")),
      attributes.getAttributeList(Attributes.Name.class));
    assertEquals(
        Collections.singletonList(new Attributes.InputBuffer(1, 2)),
        attributes.getAttributeList(Attributes.InputBuffer.class));
  }
  
  @Test
  public void mustGetAttributeByClass() {
    assertEquals(
      new Attributes.Name("a"),
      attributes.getAttribute(Attributes.Name.class, new Attributes.Name("default")));
  }
  
}
