/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.japi;

import akka.japi.pf.FI;
import akka.japi.pf.Match;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.Test;
import scala.MatchError;

import static org.junit.Assert.*;

public class MatchBuilderTest {

  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Test
  public void shouldPassBasicMatchTest() {
    Match<Object, Double> pf = Match.create(Match.match(Integer.class, new FI.Apply<Integer, Double>() {
      @Override
      public Double apply(Integer integer) {
        return integer * 10.0;
      }
    }).match(Number.class, new FI.Apply<Number, Double>() {
      @Override
      public Double apply(Number number) {
        return number.doubleValue() * (-10.0);
      }
    }));

    assertTrue("An integer should be multiplied by 10", new Double(47110).equals(pf.match(new Integer(4711))));
    assertTrue("A double should be multiplied by -10", new Double(-47110).equals(pf.match(new Double(4711))));

    exception.expect(MatchError.class);
    assertFalse("A string should throw a MatchError", new Double(4711).equals(pf.match("4711")));
  }
}
