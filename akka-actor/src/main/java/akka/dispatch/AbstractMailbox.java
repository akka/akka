/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class AbstractMailbox {
    final static VarHandle mailboxStatusHandle;
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
}
