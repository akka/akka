/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor.mailbox;

//#custom-mailbox-type
import scala.Option;
import com.typesafe.config.Config;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.ExtendedActorSystem;
import akka.dispatch.MailboxType;
import akka.dispatch.MessageQueue;

public class MyDurableMailboxType implements MailboxType {

  public MyDurableMailboxType(ActorSystem.Settings settings, Config config) {
  }

  @Override 
  public MessageQueue create(Option<ActorRef> owner,
      Option<ActorSystem> system) {
    if (owner.isEmpty())
      throw new IllegalArgumentException("requires an owner " +
          "(i.e. does not work with BalancingDispatcher)");
    return new MyDurableMessageQueue(owner.get(), 
      (ExtendedActorSystem) system.get());
  }
}
//#custom-mailbox-type