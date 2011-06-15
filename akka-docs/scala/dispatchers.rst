Dispatchers (Scala)
===================

.. sidebar:: Contents

   .. contents:: :local:
   
Module stability: **SOLID**

The Dispatcher is an important piece that allows you to configure the right semantics and parameters for optimal performance, throughput and scalability. Different Actors have different needs.

Akka supports dispatchers for both event-driven lightweight threads, allowing creation of millions of threads on a single workstation, and thread-based Actors, where each dispatcher is bound to a dedicated OS thread.

The event-based Actors currently consume ~600 bytes per Actor which means that you can create more than 6.5 million Actors on 4 GB RAM.

Default dispatcher
------------------

For most scenarios the default settings are the best. Here we have one single event-based dispatcher for all Actors created. The dispatcher used is this one:

.. code-block:: scala

  Dispatchers.globalExecutorBasedEventDrivenDispatcher

But if you feel that you are starting to contend on the single dispatcher (the 'Executor' and its queue) or want to group a specific set of Actors for a dedicated dispatcher for better flexibility and configurability then you can override the defaults and define your own dispatcher. See below for details on which ones are available and how they can be configured.

Setting the dispatcher
----------------------

Normally you set the dispatcher from within the Actor itself. The dispatcher is defined by the 'dispatcher: MessageDispatcher' member field in 'ActorRef'.

.. code-block:: scala

  class MyActor extends Actor {
    self.dispatcher = ... // set the dispatcher
     ...
  }

You can also set the dispatcher for an Actor **before** it has been started:

.. code-block:: scala

  actorRef.dispatcher = dispatcher

Types of dispatchers
--------------------

There are six different types of message dispatchers:

* Thread-based
* Event-based
* Priority event-based
* Work-stealing

Factory methods for all of these, including global versions of some of them, are in the 'akka.dispatch.Dispatchers' object.

Let's now walk through the different dispatchers in more detail.

Thread-based
^^^^^^^^^^^^

The 'ThreadBasedDispatcher' binds a dedicated OS thread to each specific Actor. The messages are posted to a 'LinkedBlockingQueue' which feeds the messages to the dispatcher one by one. A 'ThreadBasedDispatcher' cannot be shared between actors. This dispatcher has worse performance and scalability than the event-based dispatcher but works great for creating "daemon" Actors that consumes a low frequency of messages and are allowed to go off and do their own thing for a longer period of time. Another advantage with this dispatcher is that Actors do not block threads for each other.

It would normally by used from within the actor like this:

.. code-block:: scala

  class MyActor extends Actor {
    self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)
    ...
  }

Event-based
^^^^^^^^^^^

The 'ExecutorBasedEventDrivenDispatcher' binds a set of Actors to a thread pool backed up by a 'BlockingQueue'. This dispatcher is highly configurable and supports a fluent configuration API to configure the 'BlockingQueue' (type of queue, max items etc.) as well as the thread pool.

The event-driven dispatchers **must be shared** between multiple Actors. One best practice is to let each top-level Actor, e.g. the Actors you define in the declarative supervisor config, to get their own dispatcher but reuse the dispatcher for each new Actor that the top-level Actor creates. But you can also share dispatcher between multiple top-level Actors. This is very use-case specific and needs to be tried out on a case by case basis. The important thing is that Akka tries to provide you with the freedom you need to design and implement your system in the most efficient way in regards to performance, throughput and latency.

It comes with many different predefined BlockingQueue configurations:
* Bounded LinkedBlockingQueue
* Unbounded LinkedBlockingQueue
* Bounded ArrayBlockingQueue
* Unbounded ArrayBlockingQueue
* SynchronousQueue

You can also set the rejection policy that should be used, e.g. what should be done if the dispatcher (e.g. the Actor) can't keep up and the mailbox is growing up to the limit defined. You can choose between four different rejection policies:

* java.util.concurrent.ThreadPoolExecutor.CallerRuns - will run the message processing in the caller's thread as a way to slow him down and balance producer/consumer
* java.util.concurrent.ThreadPoolExecutor.AbortPolicy - rejected messages by throwing a 'RejectedExecutionException'
* java.util.concurrent.ThreadPoolExecutor.DiscardPolicy - discards the message (throws it away)
* java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy - discards the oldest message in the mailbox (throws it away)

You cane read more about these policies `here <http://java.sun.com/javase/6/docs/api/index.html?java/util/concurrent/RejectedExecutionHandler.html>`_.

Here is an example:

.. code-block:: scala

  import akka.actor.Actor
  import akka.dispatch.Dispatchers
  import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy

  class MyActor extends Actor {
    self.dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher(name)
      .withNewThreadPoolWithLinkedBlockingQueueWithCapacity(100)
      .setCorePoolSize(16)
      .setMaxPoolSize(128)
      .setKeepAliveTimeInMillis(60000)
      .setRejectionPolicy(new CallerRunsPolicy)
      .build
     ...
  }

This 'ExecutorBasedEventDrivenDispatcher' allows you to define the 'throughput' it should have. This defines the number of messages for a specific Actor the dispatcher should process in one single sweep.
Setting this to a higher number will increase throughput but lower fairness, and vice versa. If you don't specify it explicitly then it uses the default value defined in the 'akka.conf' configuration file:

.. code-block:: ruby

  actor {
    throughput = 5
  }

If you don't define a the 'throughput' option in the configuration file then the default value of '5' will be used.

Browse the `ScalaDoc <scaladoc>`_ or look at the code for all the options available.

Priority event-based
^^^^^^^^^^^^^^^^^^^^

Sometimes it's useful to be able to specify priority order of messages, that is done by using PriorityExecutorBasedEventDrivenDispatcher and supply
a java.util.Comparator[MessageInvocation] or use a akka.dispatch.PriorityGenerator (recommended):

Creating a PriorityExecutorBasedEventDrivenDispatcher using PriorityGenerator:

.. code-block:: scala

  import akka.dispatch._
  import akka.actor._
  
  val gen = PriorityGenerator { // Create a new PriorityGenerator, lower prio means more important
      case 'highpriority => 0   // 'highpriority messages should be treated first if possible
      case 'lowpriority  => 100 // 'lowpriority messages should be treated last if possible
      case otherwise     => 50    // We default to 50
   }
  
   val a = Actor.actorOf( // We create a new Actor that just prints out what it processes
         new Actor {
         def receive = {
           case x => println(x)
         }
    })
  
    // We create a new Priority dispatcher and seed it with the priority generator
    a.dispatcher = new PriorityExecutorBasedEventDrivenDispatcher("foo", gen) 
    a.start // Start the Actor

    a.dispatcher.suspend(a) // Suspending the actor so it doesn't start to treat the messages before we have enqueued all of them :-)

     a ! 'lowpriority
     a ! 'lowpriority
     a ! 'highpriority
     a ! 'pigdog
     a ! 'pigdog2
     a ! 'pigdog3
     a ! 'highpriority

     a.dispatcher.resume(a) // Resuming the actor so it will start treating its messages

Prints:

'highpriority
'highpriority
'pigdog
'pigdog2
'pigdog3
'lowpriority
'lowpriority

Work-stealing event-based
^^^^^^^^^^^^^^^^^^^^^^^^^

The 'ExecutorBasedEventDrivenWorkStealingDispatcher' is a variation of the 'ExecutorBasedEventDrivenDispatcher' in which Actors of the same type can be set up to share this dispatcher and during execution time the different actors will steal messages from other actors if they have less messages to process. This can be a great way to improve throughput at the cost of a little higher latency.

Normally the way you use it is to create an Actor companion object to hold the dispatcher and then set in in the Actor explicitly.

.. code-block:: scala

  object MyActor {
    val dispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher(name).build
  }

  class MyActor extends Actor {
    self.dispatcher = MyActor.dispatcher
    ...
  }

Here is an article with some more information: `Load Balancing Actors with Work Stealing Techniques <http://janvanbesien.blogspot.com/2010/03/load-balancing-actors-with-work.html>`_
Here is another article discussing this particular dispatcher: `Flexible load balancing with Akka in Scala <http://vasilrem.com/blog/software-development/flexible-load-balancing-with-akka-in-scala/>`_

Making the Actor mailbox bounded
--------------------------------

Global configuration
^^^^^^^^^^^^^^^^^^^^

You can make the Actor mailbox bounded by a capacity in two ways. Either you define it in the configuration file under 'default-dispatcher'. This will set it globally.

.. code-block:: ruby

  actor {
    default-dispatcher {
      mailbox-capacity = -1            # If negative (or zero) then an unbounded mailbox is used (default)
                                       # If positive then a bounded mailbox is used and the capacity is set to the number specified
    }
  }

Per-instance based configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can also do it on a specific dispatcher instance.

For the 'ExecutorBasedEventDrivenDispatcher' and the 'ExecutorBasedWorkStealingDispatcher' you can do it through their constructor

.. code-block:: scala

  class MyActor extends Actor {
    val mailboxCapacity = BoundedMailbox(capacity = 100)
    self.dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher(name, throughput, mailboxCapacity).build
     ...
  }

For the 'ThreadBasedDispatcher', it is non-shareable between actors, and associates a dedicated Thread with the actor.
Making it bounded (by specifying a capacity) is optional, but if you do, you need to provide a pushTimeout (default is 10 seconds). When trying to send a message to the Actor it will throw a MessageQueueAppendFailedException("BlockingMessageTransferQueue transfer timed out") if the message cannot be added to the mailbox within the time specified by the pushTimeout.

.. code-block:: scala

  class MyActor extends Actor {
    import akka.util.duration._
    self.dispatcher = Dispatchers.newThreadBasedDispatcher(self, mailboxCapacity = 100,
      pushTimeOut = 10 seconds)
     ...
  }


