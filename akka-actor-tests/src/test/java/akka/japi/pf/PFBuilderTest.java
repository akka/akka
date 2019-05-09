/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.japi.pf;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.PartialFunction;

import static org.junit.Assert.*;

@SuppressWarnings("serial")
public class PFBuilderTest extends JUnitSuite {

  @Test
  public void pfbuilder_matchAny_should_infer_declared_input_type_for_lambda() {
    PartialFunction<String, Integer> pf =
        new PFBuilder<String, Integer>()
            .matchEquals("hello", s -> 1)
            .matchAny(s -> Integer.valueOf(s))
            .build();

    assertTrue(pf.isDefinedAt("hello"));
    assertTrue(pf.isDefinedAt("42"));
    assertEquals(42, pf.apply("42").intValue());
  }
}
