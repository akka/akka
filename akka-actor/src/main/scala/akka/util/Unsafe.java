/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */


package akka.util;

/**
 * INTERNAL API
 */
public final class Unsafe {
    public final static sun.misc.Unsafe instance = scala.concurrent.util.Unsafe.instance;
}
