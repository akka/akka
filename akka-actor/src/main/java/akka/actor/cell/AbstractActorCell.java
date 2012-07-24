/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell;

import akka.actor.ActorCell;
import akka.util.Unsafe;

final class AbstractActorCell {
    final static long mailboxOffset;
    final static long childrenOffset;
    final static long nextNameOffset;

    static {
        try {
          mailboxOffset = Unsafe.instance.objectFieldOffset(ActorCell.class.getDeclaredField("akka$actor$cell$Dispatch$$_mailboxDoNotCallMeDirectly"));
          childrenOffset = Unsafe.instance.objectFieldOffset(ActorCell.class.getDeclaredField("akka$actor$cell$Children$$_childrenRefsDoNotCallMeDirectly"));
          nextNameOffset = Unsafe.instance.objectFieldOffset(ActorCell.class.getDeclaredField("akka$actor$cell$Children$$_nextNameDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
