/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.dispatcher;

//#mailbox-implementation-example
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.dispatch.Envelope;
import akka.dispatch.MailboxType;
import akka.dispatch.MessageQueue;
import akka.dispatch.ProducesMessageQueue;
import com.typesafe.config.Config;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import scala.Option;

public class MyUnboundedJMailbox implements MailboxType,
  ProducesMessageQueue<MyUnboundedJMailbox.MyMessageQueue> {

  // This is the MessageQueue implementation
  public static class MyMessageQueue implements MessageQueue,
    MyUnboundedJMessageQueueSemantics {
    private final Queue<Envelope> queue =
      new ConcurrentLinkedQueue<Envelope>();

    // these must be implemented; queue used as example
    public void enqueue(ActorRef receiver, Envelope handle) {
      queue.offer(handle);
    }
    public Envelope dequeue() { return queue.poll(); }
    public int numberOfMessages() { return queue.size(); }
    public boolean hasMessages() { return !queue.isEmpty(); }
    public void cleanUp(ActorRef owner, MessageQueue deadLetters) {
      for (Envelope handle: queue) {
        deadLetters.enqueue(owner, handle);
      }
    }
  }

  // This constructor signature must exist, it will be called by Akka
  public MyUnboundedJMailbox(ActorSystem.Settings settings, Config config) {
    // put your initialization code here
  }

  // The create method is called to create the MessageQueue
  public MessageQueue create(Option<ActorRef> owner, Option<ActorSystem> system) {
    return new MyMessageQueue();
  }
}
//#mailbox-implementation-example
