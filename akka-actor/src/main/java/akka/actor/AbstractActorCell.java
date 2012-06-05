/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor;

import akka.util.Unsafe;

final class AbstractActorCell {
    final static long mailboxOffset;
    final static long childrenOffset;

    static {
        try {
          mailboxOffset = Unsafe.instance.objectFieldOffset(ActorCell.class.getDeclaredField("_mailboxDoNotCallMeDirectly"));
          childrenOffset = Unsafe.instance.objectFieldOffset(ActorCell.class.getDeclaredField("_childrenRefsDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
