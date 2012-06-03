package akka.rcl.impl;

import akka.actor.ReflectiveDynamicAccess;

public class ThreadLocalReflectiveDynamicAccess extends ReflectiveDynamicAccess {

    private final ClassLoader classLoader;

    private ThreadLocal<ClassLoader> threadLocalCl = new ThreadLocal<ClassLoader>(){
        @Override protected ClassLoader initialValue() {
            return classLoader;
        }
    };

    public ThreadLocalReflectiveDynamicAccess(ClassLoader classLoader) {
        super(classLoader);
        this.classLoader = classLoader;
    }

    public void removeThreadLocalClassLoader() {
        threadLocalCl.remove();
    }

    public void setThreadLocalClassLoader(ClassLoader cl) {
        threadLocalCl.set(cl);
    }

    @Override public ClassLoader classLoader() {
        return threadLocalCl.get();
    }
}
