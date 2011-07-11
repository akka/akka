package sample.camel;

/**
 * @author Martin Krasser
 */
public class BeanImpl implements BeanIntf {

    public String foo(String s) {
        return "hello " + s;
    }

}
