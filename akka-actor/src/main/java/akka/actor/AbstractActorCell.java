/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor;

import akka.util.Unsafe;

final class AbstractActorCell {
    final static long mailboxOffset;

    static {
        try {
          mailboxOffset = Unsafe.instance.objectFieldOffset(ActorCell.class.getDeclaredField("_mailboxDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
