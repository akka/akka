/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor.mailbox;

//#durable-message-queue
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Callable;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ExtendedActorSystem;
import akka.actor.mailbox.DurableMessageQueueWithSerialization;
import akka.dispatch.Envelope;
import akka.dispatch.MessageQueue;
import akka.pattern.CircuitBreaker;

public class MyDurableMessageQueue extends DurableMessageQueueWithSerialization {

  public MyDurableMessageQueue(ActorRef owner, ExtendedActorSystem system) {
    super(owner, system);
  }

  private final QueueStorage storage = new QueueStorage();
  // A real-world implementation would use configuration to set the last 
  // three parameters below
  private final CircuitBreaker breaker = CircuitBreaker.create(system().scheduler(),
    5, Duration.create(30, "seconds"), Duration.create(1, "minute"));

  @Override
  public void enqueue(ActorRef receiver, final Envelope envelope) {
    breaker.callWithSyncCircuitBreaker(new Callable<Object>() {
      @Override
      public Object call() {
        byte[] data = serialize(envelope);
        storage.push(data);
        return null;
      }
    });
  }

  @Override
  public Envelope dequeue() {
    return breaker.callWithSyncCircuitBreaker(new Callable<Envelope>() {
      @Override
      public Envelope call() {
        byte[] data = storage.pull();
        if (data == null)
          return null;
        else
          return deserialize(data);
      }
    });
  }

  @Override
  public boolean hasMessages() {
    return breaker.callWithSyncCircuitBreaker(new Callable<Boolean>() {
      @Override
      public Boolean call() {
        return !storage.isEmpty();
      }
    });
  }

  @Override
  public int numberOfMessages() {
    return breaker.callWithSyncCircuitBreaker(new Callable<Integer>() {
      @Override
      public Integer call() {
        return storage.size();
      }
    });
  }

  /**
   * Called when the mailbox is disposed.
   * An ordinary mailbox would send remaining messages to deadLetters,
   * but the purpose of a durable mailbox is to continue
   * with the same message queue when the actor is started again.
   */
  @Override
  public void cleanUp(ActorRef owner, MessageQueue deadLetters) {}

  //#dummy-queue-storage
  // dummy
  private static class QueueStorage { 
    private final ConcurrentLinkedQueue<byte[]> queue = 
      new ConcurrentLinkedQueue<byte[]>();
    public void push(byte[] data) { queue.offer(data); }
    public byte[] pull() { return queue.poll(); }
    public boolean isEmpty() { return queue.isEmpty(); }
    public int size() { return queue.size(); }
  }
  //#dummy-queue-storage
}
//#durable-message-queue