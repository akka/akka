/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.dungeon;

import akka.actor.ActorCell;
import akka.util.Unsafe;

final class AbstractActorCell {
  static final long mailboxOffset;
  static final long childrenOffset;
  static final long nextNameOffset;
  static final long functionRefsOffset;

  static {
    try {
      mailboxOffset =
          Unsafe.instance.objectFieldOffset(
              // TODO DOTTY
              ActorCell.class.getDeclaredField("_mailboxDoNotCallMeDirectly"));
      // "akka$actor$dungeon$Dispatch$$_mailboxDoNotCallMeDirectly"));
      childrenOffset =
          Unsafe.instance.objectFieldOffset(
              // TODO DOTTY
              ActorCell.class.getDeclaredField("_childrenRefsDoNotCallMeDirectly"));
      // "akka$actor$dungeon$Children$$_childrenRefsDoNotCallMeDirectly"));
      nextNameOffset =
          Unsafe.instance.objectFieldOffset(
              // TODO DOTTY
              ActorCell.class.getDeclaredField("_nextNameDoNotCallMeDirectly"));
      // "akka$actor$dungeon$Children$$_nextNameDoNotCallMeDirectly"));
      functionRefsOffset =
          Unsafe.instance.objectFieldOffset(
              // TODO DOTTY
              ActorCell.class.getDeclaredField("_functionRefsDoNotCallMeDirectly"));
      //    "akka$actor$dungeon$Children$$_functionRefsDoNotCallMeDirectly"));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }
}
