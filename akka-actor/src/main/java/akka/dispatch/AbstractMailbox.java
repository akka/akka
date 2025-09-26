/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch;

import akka.annotation.InternalApi;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * INTERNAL API
 */
@InternalApi
final class AbstractMailbox {
    private final static VarHandle mailboxStatusHandle;
    final static VarHandle systemMessageHandle;

    static {
        try {
          MethodHandles.Lookup lookup =
              MethodHandles.privateLookupIn(Mailbox.class, MethodHandles.lookup());
          mailboxStatusHandle = lookup.unreflectVarHandle(Mailbox.class.getDeclaredField("_statusDoNotCallMeDirectly"));
          systemMessageHandle = lookup.unreflectVarHandle(Mailbox.class.getDeclaredField("_systemQueueDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }

    public static int currentMailboxStatus(Mailbox mailbox) {
      return (int)mailboxStatusHandle.getVolatile(mailbox);
    }

    public static boolean updateStatus(Mailbox mailbox, int oldStatus, int newStatus) {
      return mailboxStatusHandle.compareAndSet(mailbox, oldStatus, newStatus);
    }

    public static void setStatus(Mailbox mailbox, int newStatus) {
      AbstractMailbox.mailboxStatusHandle.setVolatile(mailbox, newStatus);
    }
}
