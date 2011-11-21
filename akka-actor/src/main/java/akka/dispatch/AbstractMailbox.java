/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch;

import akka.util.Unsafe;

final class AbstractMailbox {
    final static long mailboxStatusOffset;
    final static long systemMessageOffset;

    static {
        try {
          mailboxStatusOffset = Unsafe.instance.objectFieldOffset(Mailbox.class.getDeclaredField("_status"));
          systemMessageOffset = Unsafe.instance.objectFieldOffset(Mailbox.class.getDeclaredField("_systemQueue"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
