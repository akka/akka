/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit;

import org.junit.Test;
import akka.actor.Props;

public class TestActorRefJavaCompile {

  public void shouldBeAbleToCompileWhenUsingApply() {
  	//Just a dummy call to make sure it compiles
    TestActorRef ref = TestActorRef.apply(new Props(), null);
  }
}