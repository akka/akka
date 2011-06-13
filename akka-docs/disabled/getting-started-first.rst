Getting Started Tutorial: First Chapter
=======================================

Start writing the code
----------------------

Now it's about time to start hacking.

We start by creating a ``Pi.scala`` file and adding these import statements at the top of the file:

.. includecode:: examples/Pi.scala#imports

If you are using SBT in this tutorial then create the file in the ``src/main/scala`` directory.

If you are using the command line tools then create the file wherever you want. I will create it in a directory called ``tutorial`` at the root of the Akka distribution, e.g. in ``$AKKA_HOME/tutorial/Pi.scala``.

Creating the messages
---------------------

The design we are aiming for is to have one ``Master`` actor initiating the computation, creating a set of ``Worker`` actors. Then it splits up the work into discrete chunks, and sends these chunks to the different workers in a round-robin fashion. The master waits until all the workers have completed their work and sent back results for aggregation. When computation is completed the master prints out the result, shuts down all workers and then itself.

With this in mind, let's now create the messages that we want to have flowing in the system. We need three different messages:

- ``Calculate`` -- sent to the ``Master`` actor to start the calculation
- ``Work`` -- sent from the ``Master`` actor to the ``Worker`` actors containing the work assignment
- ``Result`` -- sent from the ``Worker`` actors to the ``Master`` actor containing the result from the worker's calculation

Messages sent to actors should always be immutable to avoid sharing mutable state. In scala we have 'case classes' which make excellent messages. So let's start by creating three messages as case classes.  We also create a common base trait for our messages (that we define as being ``sealed`` in order to prevent creating messages outside our control):

.. includecode:: examples/Pi.scala#messages

Creating the worker
-------------------

Now we can create the worker actor.  This is done by mixing in the ``Actor`` trait and defining the ``receive`` method. The ``receive`` method defines our message handler. We expect it to be able to handle the ``Work`` message so we need to add a handler for this message:

.. includecode:: examples/Pi.scala#worker
   :exclude: calculate-pi

As you can see we have now created an ``Actor`` with a ``receive`` method as a handler for the ``Work`` message. In this handler we invoke the ``calculatePiFor(..)`` method, wrap the result in a ``Result`` message and send it back to the original sender using ``self.reply``. In Akka the sender reference is implicitly passed along with the message so that the receiver can always reply or store away the sender reference for future use.

The only thing missing in our ``Worker`` actor is the implementation on the ``calculatePiFor(..)`` method. While there are many ways we can implement this algorithm in Scala, in this introductory tutorial we have chosen an imperative style using a for comprehension and an accumulator:

.. includecode:: examples/Pi.scala#calculate-pi

Creating the master
-------------------

The master actor is a little bit more involved. In its constructor we need to create the workers (the ``Worker`` actors) and start them. We will also wrap them in a load-balancing router to make it easier to spread out the work evenly between the workers. Let's do that first:

.. includecode:: examples/Pi.scala#create-workers

As you can see we are using the ``actorOf`` factory method to create actors, this method returns as an ``ActorRef`` which is a reference to our newly created actor.  This method is available in the ``Actor`` object but is usually imported::

    import akka.actor.Actor._

Now we have a router that is representing all our workers in a single abstraction. If you paid attention to the code above, you saw that we were using the ``nrOfWorkers`` variable. This variable and others we have to pass to the ``Master`` actor in its constructor. So now let's create the master actor. We have to pass in three integer variables:

- ``nrOfWorkers`` -- defining how many workers we should start up
- ``nrOfMessages`` -- defining how many number chunks to send out to the workers
- ``nrOfElements`` -- defining how big the number chunks sent to each worker should be

Here is the master actor:

.. includecode:: examples/Pi.scala#master
   :exclude: message-handling

A couple of things are worth explaining further.

First, we are passing in a ``java.util.concurrent.CountDownLatch`` to the ``Master`` actor. This latch is only used for plumbing (in this specific tutorial), to have a simple way of letting the outside world knowing when the master can deliver the result and shut down. In more idiomatic Akka code, as we will see in part two of this tutorial series, we would not use a latch but other abstractions and functions like ``Channel``, ``Future`` and ``?`` to achive the same thing in a non-blocking way. But for simplicity let's stick to a ``CountDownLatch`` for now.

Second, we are adding a couple of life-cycle callback methods; ``preStart`` and ``postStop``. In the ``preStart`` callback we are recording the time when the actor is started and in the ``postStop`` callback we are printing out the result (the approximation of Pi) and the time it took to calculate it. In this call we also invoke ``latch.countDown`` to tell the outside world that we are done.

But we are not done yet. We are missing the message handler for the ``Master`` actor. This message handler needs to be able to react to two different messages:

- ``Calculate`` -- which should start the calculation
- ``Result`` -- which should aggregate the different results

The ``Calculate`` handler is sending out work to all the ``Worker`` actors and after doing that it also sends a ``Broadcast(PoisonPill)`` message to the router, which will send out the ``PoisonPill`` message to all the actors it is representing (in our case all the ``Worker`` actors). ``PoisonPill`` is a special kind of message that tells the receiver to shut itself down using the normal shutdown method; ``self.stop``. We also send a ``PoisonPill`` to the router itself (since it's also an actor that we want to shut down).

The ``Result`` handler is simpler, here we get the value from the ``Result`` message and aggregate it to our ``pi`` member variable. We also keep track of how many results we have received back, and if that matches the number of tasks sent out, the ``Master`` actor considers itself done and shuts down.

Let's capture this in code:

.. includecode:: examples/Pi.scala#master-receive

Bootstrap the calculation
-------------------------

Now the only thing that is left to implement is the runner that should bootstrap and run the calculation for us. We do that by creating an object that we call ``Pi``, here we can extend the ``App`` trait in Scala, which means that we will be able to run this as an application directly from the command line.

The ``Pi`` object is a perfect container module for our actors and messages, so let's put them all there. We also create a method ``calculate`` in which we start up the ``Master`` actor and wait for it to finish:

.. includecode:: examples/Pi.scala#app
   :exclude: actors-and-messages

That's it. Now we are done.

But before we package it up and run it, let's take a look at the full code now, with package declaration, imports and all:

.. includecode:: examples/Pi.scala
