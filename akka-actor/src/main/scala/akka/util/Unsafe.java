/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */


package akka.util;

import java.lang.reflect.Field;

public final class Unsafe {
    public final static sun.misc.Unsafe instance;
    static {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            instance = (sun.misc.Unsafe) field.get(null);
        } catch(Throwable t) {
          throw new ExceptionInInitializerError(t);
        }
    }
}
