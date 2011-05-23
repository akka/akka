package akka.spring.foo;

import akka.actor.*;

public class Foo extends TypedActor implements IFoo{

  public String foo() {
    return "foo";
  }

}
