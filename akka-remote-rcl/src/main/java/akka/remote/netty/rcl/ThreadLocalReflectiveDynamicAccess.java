package akka.remote.netty.rcl;

import akka.actor.ReflectiveDynamicAccess;
import scala.util.DynamicVariable;

public class ThreadLocalReflectiveDynamicAccess extends ReflectiveDynamicAccess {

    public final DynamicVariable<ClassLoader> dynamicVariable;

    public ThreadLocalReflectiveDynamicAccess(ClassLoader classLoader) {
        super(classLoader);
        dynamicVariable = new DynamicVariable<ClassLoader>(classLoader);
    }

    @Override
    public ClassLoader classLoader() {
        return dynamicVariable.value();
    }
}
