/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery;

import akka.util.Unsafe;

class AbstractAssociation {
    protected final static long sharedStateOffset;

    static {
        try {
            sharedStateOffset = Unsafe.instance.objectFieldOffset(Association.class.getDeclaredField("_sharedStateDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
