/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed.pubsub;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;

// #start-topic
// #lookup-topic
import akka.actor.typed.pubsub.Topic;
// #start-topic

import akka.actor.typed.pubsub.PubSub;
// #lookup-topic
public class PubSubExample {

  static class Message {
    public final String text;

    public Message(String text) {
      this.text = text;
    }
  }

  private Behavior<?> behavior =
      // #start-topic

      Behaviors.setup(
          context -> {
            ActorRef<Topic.Command<Message>> topic =
                context.spawn(Topic.create(Message.class, "my-topic"), "MyTopic");
            // #start-topic

            ActorRef<Message> subscriberActor = null;
            // #subscribe
            topic.tell(Topic.subscribe(subscriberActor));

            topic.tell(Topic.unsubscribe(subscriberActor));
            // #subscribe

            // #publish
            topic.tell(Topic.publish(new Message("Hello Subscribers!")));
            // #publish

            return Behaviors.empty();
          });

  private Behavior<?> lookupExample =
          // #lookup-topic

          Behaviors.setup(
                  context -> {
                      ActorRef<Topic.Command<Message>> topic =
                        PubSub.get(context.getSystem()).topic(Message.class, "my-topic");
                  // #lookup-topic
                      return Behaviors.empty();
                  }
          );
}
