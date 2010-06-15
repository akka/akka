package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public class Pojo {

    public String foo(String s) {
        return String.format("foo: %s", s);
    }

}