package sample.camel;

import akka.actor.TypedActor;
/**
 * @author Martin Krasser
 */
public class BeanImpl extends TypedActor implements BeanIntf {

    public String foo(String s) {
        return "hello " + s;
    }

}
