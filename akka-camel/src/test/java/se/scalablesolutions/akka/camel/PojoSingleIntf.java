package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public interface PojoSingleIntf {

    @consume("direct:foo")
    public void foo(String b);
}
