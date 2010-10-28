package akka.spring;

import akka.actor.*;

public class SampleBean extends TypedActor implements SampleBeanIntf {

    private boolean down;

    public SampleBean() {
        down = false;
    }

    public boolean down() {
      return down;
    }

    public String foo(String s) {
        return "hello " + s;
    }

    @Override
    public void postStop() {
        down = true;
    }
 }
