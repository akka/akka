/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit;

import akka.actor.Actor;
import akka.actor.Props;

public class TestActorRefJavaCompile {

  public void shouldBeAbleToCompileWhenUsingApply() {
  	//Just a dummy call to make sure it compiles
    TestActorRef<Actor> ref = TestActorRef.apply(Props.empty(), null);
    ref.toString();
  }
}
