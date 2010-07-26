package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public class PojoRemote {

    @consume("direct:remote-active-object")
    public String foo(String s) {
        return String.format("remote typed actor: %s", s);
    }

}
