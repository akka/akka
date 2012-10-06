.. _howto-java:

######################
HowTo: Common Patterns
######################

This section lists common actor patterns which have been found to be useful,
elegant or instructive. Anything is welcome, example topics being message
routing strategies, supervision patterns, restart handling, etc. As a special
bonus, additions to this section are marked with the contributor’s name, and it
would be nice if every Akka user who finds a recurring pattern in his or her
code could share it for the profit of all. Where applicable it might also make
sense to add to the ``akka.pattern`` package for creating an `OTP-like library
<http://www.erlang.org/doc/man_index.html>`_.

You might find some of the patterns described in the Scala chapter of 
:ref:`howto-scala` useful even though the example code is written in Scala.

Exception propagation in actor hierarchy using supervision
==========================================================

*Contributed by: Rick Latrine*

A nice way to enter the actor world from java is the use of Patterns.ask().
This method starts a temporary actor to forward the message and collect the result from the actor to be "asked".
In case of errors within the asked actor the default supervision handling will take over.
The caller of Patterns.ask() will not be notified.

If that caller is interested in such an exception, he must reply to the temporary actor with Status.Failure(Throwable).
Behind the asked actor a complex actor hierarchy might be spawned to accomplish asynchronous work.
Then supervision is the established way to control error handling.

Unfortunately the asked actor must know about supervision and must catch the exceptions.
Such an actor is unlikely to be reused in a different actor hierarchy and contains crippled try/catch blocks.

This pattern provides a way to encapsulate supervision and error propagation to the temporary actor.
Finally the exception occurred in the asked actor is thrown by Patterns.ask().

Let's have a look at the example code:

.. code-block:: java

   public class JavaAsk extends UntypedActor {
   		private ActorRef targetActor;
   		private ActorRef caller;
   		
   		private static class AskParam {
   			Props props;
   			Object message;
   			AskParam(Props props, Object message) {
   				this.props = props;
   				this.message = message;
   			}
   		}
   		
   		@Override
   		public SupervisorStrategy supervisorStrategy() {
   			return new OneForOneStrategy(0, Duration.Zero(), new Function<Throwable, Directive>() {
   				public Directive apply(Throwable cause) {
   					caller.tell(new Status.Failure(cause));
   					return SupervisorStrategy.stop();
   				}
   			});
   		}
   
   		@Override
   		public void onReceive(Object message) throws Exception {
   			if (message instanceof AskParam) {
   				AskParam param = (AskParam) message;
   				caller = getSender();
   				targetActor = getContext().actorOf(param.props);
   				targetActor.forward(param.message, getContext());
   			} else
   				unhandled(message);
   		}
   
   		public static void ask(ActorSystem system, Props props, Object message, Timeout timeout) throws Exception {
   			ActorRef javaAsk = system.actorOf(Props.apply(JavaAsk.class));
   			try {
   				AskParam param = new AskParam(props, message);
   				Future<Object> finished = Patterns.ask(javaAsk, param, timeout);
   				Await.result(finished, timeout.duration());
   			} finally {
   				system.stop(javaAsk);
   			}
   		}
   	}
   }

In the ask method the parent actor is started.
The parent is sent which child it should create and which message is to be forwarded.

On receiving these parameteres the parent actor creates the target/child actor.
The message is forwarded in order to let the child actor reply the result directly to the temporary actor.

In case of an exception the supervisor tells the temporary actor which exception was thrown.
Afterwards the actor hierarchy is stopped.
The exception will be thrown by the ask method.

Finally we are able to execute an actor and receive the results or exceptions synchronously.


Template Pattern
================

*Contributed by: N. N.*

This is an especially nice pattern, since it does even come with some empty example code:

.. includecode:: code/docs/pattern/JavaTemplate.java
   :include: all-of-it
   :exclude: uninteresting-stuff

.. note::

   Spread the word: this is the easiest way to get famous!

Please keep this pattern at the end of this file.

