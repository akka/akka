/*
 * Copyright (C) 2018-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed;

public class MailboxSelectorTest {
  // Compile time only test to verify
  // mailbox factories are accessible from Java

  private MailboxSelector def = MailboxSelector.defaultMailbox();
  private MailboxSelector bounded = MailboxSelector.bounded(1000);
  private MailboxSelector conf = MailboxSelector.fromConfig("somepath");
}
