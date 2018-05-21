/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.CommandHandlerBuilder;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.PersistentBehavior;

public class InDepthPersistentBehaviorTest {

  static class Blog {

    //#event
    interface BlogEvent {
    }
    public static class PostAdded implements BlogEvent {
      private final String postId;
      private final PostContent content;

      public PostAdded(String postId, PostContent content) {
        this.postId = postId;
        this.content = content;
      }
    }

    public static class BodyChanged implements BlogEvent {
      private final String postId;
      private final String newBody;

      public BodyChanged(String postId, String newBody) {
        this.postId = postId;
        this.newBody = newBody;
      }
    }

    public static class Published implements BlogEvent {
      private final String postId;

      public Published(String postId) {
        this.postId = postId;
      }
    }
    //#event

    //#state
    public static class BlogState {
      final PostContent postContent;
      final boolean published;

      BlogState(PostContent postContent, boolean published) {
        this.postContent = postContent;
        this.published = published;
      }

      public BlogState withContent(PostContent newContent) {
        return new BlogState(newContent, this.published);
      }


      public String postId() {
        return postContent.postId;
      }
    }
    //#state

    //#commands
    public interface BlogCommand {
    }
    public static class AddPost implements BlogCommand {
      final PostContent content;
      final ActorRef<AddPostDone> replyTo;

      public AddPost(PostContent content, ActorRef<AddPostDone> replyTo) {
        this.content = content;
        this.replyTo = replyTo;
      }
    }
    public static class AddPostDone implements BlogCommand {
      final String postId;

      public AddPostDone(String postId) {
        this.postId = postId;
      }
    }
    public static class GetPost implements BlogCommand {
      final ActorRef<PostContent> replyTo;

      public GetPost(ActorRef<PostContent> replyTo) {
        this.replyTo = replyTo;
      }
    }
    public static class ChangeBody implements BlogCommand {
      final String newBody;
      final ActorRef<Done> replyTo;

      public ChangeBody(String newBody, ActorRef<Done> replyTo) {
        this.newBody = newBody;
        this.replyTo = replyTo;
      }
    }
    public static class Publish implements BlogCommand {
      final ActorRef<Done> replyTo;

      public Publish(ActorRef<Done> replyTo) {
        this.replyTo = replyTo;
      }
    }
    public static class PassivatePost implements BlogCommand {

    }
    public static class PostContent implements BlogCommand {
      final String postId;
      final String title;
      final String body;

      public PostContent(String postId, String title, String body) {
        this.postId = postId;
        this.title = title;
        this.body = body;
      }
    }
    //#commands

    //#behavior
    public static class BlogBehavior extends PersistentBehavior<BlogCommand, BlogEvent, BlogState> {

      //#initial-command-handler
      @Override
      public CommandHandler<BlogCommand, BlogEvent, BlogState> commandHandler() {
        return commandHandlerBuilder()
                .matchCommand(AddPost.class, (ctx, cmd) -> {
                  PostAdded event = new PostAdded(cmd.content.postId, cmd.content);
                  return Effect()
                          .persist(event)
                          .andThen(() -> cmd.replyTo.tell(new AddPostDone(cmd.content.postId)));
                })
                .matchCommand(PassivatePost.class, (ctx, cmd) -> Effect().stop())
                .build();
      }
      //#initial-command-handler



      public BlogBehavior(String persistenceId) {
        super(persistenceId);
      }



      //#post-added-command-handler
      @Override
      public CommandHandler<BlogCommand, BlogEvent, BlogState> commandHandler(BlogState blogState) {
        return commandHandlerBuilder()
                .matchCommand(ChangeBody.class, (ctx, cmd) -> {
                  BodyChanged event = new BodyChanged(blogState.postId(), cmd.newBody);
                  return Effect().persist(event).andThen(() -> cmd.replyTo.tell(Done.getInstance()));
                })
                .matchCommand(Publish.class, (ctx, cmd) -> Effect()
                        .persist(new Published(blogState.postId())).andThen(() -> {
                          System.out.println("Blog post published: " + blogState.postId());
                          cmd.replyTo.tell(Done.getInstance());
                        }))
                .matchCommand(GetPost.class, (ctx, cmd) -> {
                  cmd.replyTo.tell(blogState.postContent);
                  return Effect().none();
                })
                .matchCommand(AddPost.class, (ctx, cmd) -> Effect().unhandled())
                .matchCommand(PassivatePost.class, (ctx, cmd) -> Effect().stop())
                .build();
      }
      //#post-added-command-handler

      @Override
      public EventHandler<BlogEvent, BlogState> eventHandler() {
        return eventHandlerBuilder()
                .matchEvent(PostAdded.class, event -> new BlogState(event.content, false))
                .build();
      }

      //#event-handler
      @Override
      public EventHandler<BlogEvent, BlogState> eventHandler(BlogState blogState) {
        return eventHandlerBuilder()
                .matchEvent(BodyChanged.class, newBody ->
                        new BlogState(new PostContent(blogState.postContent.postId, blogState.postContent.title, newBody.newBody), blogState.published))
                .matchEvent(Published.class,  event -> new BlogState(blogState.postContent, true))
                .build();
      }
      //#event-handler
    }
    //#behavior
  }

}
