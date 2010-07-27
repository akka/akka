package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public interface PojoRemoteIntf {

    @consume("direct:remote-active-object")
    public String foo(String s);
}
