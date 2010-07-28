package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.annotation.consume;
import se.scalablesolutions.akka.actor.*;

/**
 * @author Martin Krasser
 */
public class PojoSingle extends TypedActor implements PojoSingleIntf {

    @consume("direct:foo")
    public void foo(String b) {
    }

}
