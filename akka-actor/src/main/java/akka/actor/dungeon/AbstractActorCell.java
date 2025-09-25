/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.dungeon;

import akka.actor.ActorCell;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

final class AbstractActorCell {
  static final VarHandle mailboxHandle;
  static final VarHandle childrenHandle;
  static final VarHandle nextNameHandle;
  static final VarHandle functionRefsHandle;

  static {
    try {
      MethodHandles.Lookup lookup =
          MethodHandles.privateLookupIn(ActorCell.class, MethodHandles.lookup());
      Field mailboxField =
          ActorCell.class.getDeclaredField(
              "akka$actor$dungeon$Dispatch$$_mailboxDoNotCallMeDirectly");
      mailboxField.setAccessible(true);
      mailboxHandle = lookup.unreflectVarHandle(mailboxField);

      Field childrenField =
          ActorCell.class.getDeclaredField(
              "akka$actor$dungeon$Children$$_childrenRefsDoNotCallMeDirectly");
      childrenField.setAccessible(true);
      childrenHandle = lookup.unreflectVarHandle(childrenField);

      Field nextNameField =
          ActorCell.class.getDeclaredField(
              "akka$actor$dungeon$Children$$_nextNameDoNotCallMeDirectly");
      nextNameField.setAccessible(true);
      nextNameHandle = lookup.unreflectVarHandle(nextNameField);

      Field functionRefsField =
          ActorCell.class.getDeclaredField(
              "akka$actor$dungeon$Children$$_functionRefsDoNotCallMeDirectly");
      functionRefsField.setAccessible(true);
      functionRefsHandle = lookup.unreflectVarHandle(functionRefsField);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }
}
