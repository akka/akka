/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.japi.pf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import scala.PartialFunction;

public class PFBuilderTest extends JUnitSuite {

  @Test
  public void pfbuilder_matchAny_should_infer_declared_input_type_for_lambda() {
    PartialFunction<String,Integer> pf = new PFBuilder<String,Integer>()
      .matchEquals("hello", s -> 1)
      .matchAny(s -> Integer.valueOf(s))
      .build();
      
    assertTrue(pf.isDefinedAt("hello"));
    assertTrue(pf.isDefinedAt("42"));
    assertEquals(42, pf.apply("42").intValue());
  }
  
  interface TestInner {
      public boolean exists();
  }
  
  @Test
  public void typed_pfbuilder_can_match_with_only_predicate_argument() {
    PartialFunction<String,Integer> pf = Match
      .match(String::isEmpty, emptyString -> 42)
      .build();
    
    assertTrue(pf.isDefinedAt(""));
    assertEquals(42, pf.apply("").intValue());
    assertFalse(pf.isDefinedAt("42"));
  }
  
  @Test
  public void typed_pfbuilder_can_match_with_anonymous_inner_class() {
    PartialFunction<Object,Integer> pf = new PFBuilder<Object,Integer>()
      .match(TestInner::exists, obj -> 42)
      .build();
    
    assertFalse(pf.isDefinedAt(new TestInner() {
        @Override
        public boolean exists() {
            return false;
        }
    }));
    assertTrue(pf.isDefinedAt(new TestInner() {
        @Override
        public boolean exists() {
            return true;
        }
    }));
    assertEquals(42, pf.apply(new TestInner() {
        @Override
        public boolean exists() {
            return true;
        }
    }).intValue());
  }
  
  @Test
  public void typed_pfbuilder_can_match_with_only_predicate_argument_of_subclass() {
    PartialFunction<Number,Integer> pf = new PFBuilder<Number,Integer>()
      .match((Integer i) -> i == 0, n -> 42)
      .build();
    
    assertFalse(pf.isDefinedAt(1.0f));
    assertTrue(pf.isDefinedAt(0));
    assertEquals(42, pf.apply(0).intValue());
  }
}
