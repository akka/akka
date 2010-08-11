package se.scalablesolutions.akka.spring.foo;

import se.scalablesolutions.akka.actor.*;

public class Foo extends TypedActor implements IFoo{

  public String foo() {
    return "foo";
  }

}
