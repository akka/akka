/**
 * Copyright (C) 2015-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.ClassRule;
import org.junit.Test;

import akka.stream.Attributes;
import akka.stream.StreamTest;
import akka.stream.testkit.AkkaSpec;

public class AttributesTest extends StreamTest {

  public AttributesTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("AttributesTest",
    AkkaSpec.testConf());

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
      new Attributes.Name("b"),
      attributes.getAttribute(Attributes.Name.class, new Attributes.Name("default")));
  }

}
