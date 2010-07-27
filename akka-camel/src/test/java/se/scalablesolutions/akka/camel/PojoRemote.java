package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.annotation.consume;
import se.scalablesolutions.akka.actor.*;

/**
 * @author Martin Krasser
 */
public class PojoRemote extends TypedActor implements PojoRemoteIntf {

    @consume("direct:remote-active-object")
    public String foo(String s) {
        return String.format("remote typed actor: %s", s);
    }

}
