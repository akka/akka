/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.junit.Test;

import akka.stream.OperationAttributes;

public class OperationAttributesTest  {
  
  final OperationAttributes attributes = 
      OperationAttributes.name("a")
      .and(OperationAttributes.name("b"))
      .and(OperationAttributes.inputBuffer(1, 2));

  @Test
  public void mustGetAttributesByClass() {
    assertEquals(
      Arrays.asList(new OperationAttributes.Name("a"), new OperationAttributes.Name("b")),
      attributes.getAttributes(OperationAttributes.Name.class));
    assertEquals(
        Arrays.asList(new OperationAttributes.InputBuffer(1, 2)),
        attributes.getAttributes(OperationAttributes.InputBuffer.class));
  }
  
  @Test
  public void mustGetAttributeByClass() {
    assertEquals(
      new OperationAttributes.Name("a"),
      attributes.getAttribute(OperationAttributes.Name.class, new OperationAttributes.Name("default")));
  }
  
}
