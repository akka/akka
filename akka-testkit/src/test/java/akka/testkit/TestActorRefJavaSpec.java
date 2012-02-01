/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit;

import org.junit.Test;
import akka.actor.Props;

import static org.junit.Assert.*;

public class TestActorRefJavaSpec {

  @Test
  public void shouldBeAbleToUseApply() {
  	//Just a dummy call to make sure it compiles
    TestActorRef ref = TestActorRef.apply(new Props(), null);
  }
}