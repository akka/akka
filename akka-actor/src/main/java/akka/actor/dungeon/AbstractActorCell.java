/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.dungeon;

import akka.actor.ActorCell;
import akka.util.Unsafe;

final class AbstractActorCell {
    final static long mailboxOffset;
    final static long childrenOffset;
    final static long nextNameOffset;

    static {
        try {
          mailboxOffset = Unsafe.instance.objectFieldOffset(ActorCell.class.getDeclaredField("akka$actor$dungeon$Dispatch$$_mailboxDoNotCallMeDirectly"));
          childrenOffset = Unsafe.instance.objectFieldOffset(ActorCell.class.getDeclaredField("akka$actor$dungeon$Children$$_childrenRefsDoNotCallMeDirectly"));
          nextNameOffset = Unsafe.instance.objectFieldOffset(ActorCell.class.getDeclaredField("akka$actor$dungeon$Children$$_nextNameDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
