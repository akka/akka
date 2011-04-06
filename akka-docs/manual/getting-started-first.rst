Getting Started Tutorial: First Chapter
=======================================

Introduction
------------

Welcome to the first tutorial on how to get started with Akka and Scala. We assume that you already know what Akka and Scala is and will now focus on the steps necessary to start your first project.

There are two variations of this first tutorial:

- creating a standalone project and run it from the command line
- creating a SBT (Simple Build Tool) project and running it from within SBT

Since they are so similar we will present them both in this tutorial. The sample application that we will create is using actors to calculate the value of Pi. Is using an algorithm that is easily parallelizable and therefore suits the actor model very well, but is generic enough to suit as a starting point for all kinds of Master/Worker style problems.

Here is the formula for the algorithm we will use:

.. image:: pi-formula.png

In this particular algorithm the master splits the series into chunks which are sent out to each worker actor to be processed, when each worker has processed its chunk it sends a result back to the master which aggregates to total result.

Downloading and installing Akka
-------------------------------

This tutorial assumes that you have Jave 1.6 or later installed on you machine and ``java`` on your ``PATH``.

The first thing we need to do is to download Akka. Let's get the 1.1 distribution from `http://akka.io/downloads <http://akka.io/downloads/>`_. Once you have downloaded the distribution unzip it in the folder you would like to have Akka installed in, in my case I choose to install it in ``/Users/jboner/tools/``, simply by unzipping it to this directory.

You need to do one more thing in order to install Akka properly and that is to set the ``AKKA_HOME`` environment variable to the root of the distribution. In my case I'm opening up a shell and navigating down to the distribution and setting the ``AKKA_HOME`` variable::

    $ cd /Users/jboner/tools/akka-1.1
    $ export AKKA_HOME=`pwd`
    $ echo $AKKA_HOME
    /Users/jboner/tools/akka-1.1

If we now take a look at what we have in this distribution, looks like this::

    $ ls -l
    total 16944
    drwxr-xr-x   7 jboner  staff      238 Apr  6 11:15 .
    drwxr-xr-x  28 jboner  staff      952 Apr  6 11:16 ..
    drwxr-xr-x  17 jboner  staff      578 Apr  6 11:16 deploy
    drwxr-xr-x  26 jboner  staff      884 Apr  6 11:16 dist
    drwxr-xr-x   3 jboner  staff      102 Apr  6 11:15 lib_managed
    -rwxr-xr-x   1 jboner  staff  8674105 Apr  6 11:15 scala-library.jar
    drwxr-xr-x   4 jboner  staff      136 Apr  6 11:16 scripts

- In the ``dist`` directory we have all the Akka JARs, including sources and docs.
- In the ``lib_managed/compile`` directory we have all the Akka's dependency JARs.
- In the ``deploy`` directory we have all the sample JARs.
- In the ``scripts`` directory we have scripts for running Akka.
- Finallly the ``scala-library.jar`` is the JAR for the latest Scala distribution that Akka depends on.

Downloading and installing Scala
--------------------------------

If you want to be able to build and run the tutorial sample from the command line then you have to install the Scala distribution. If you prefer to use SBT to build and run the sample then you need can skip this section and jump to the next one.

Scala can be downloaded from `http://www.scala-lang.org/downloads <http://www.scala-lang.org/downloads>`_. Browse there and download the Scala 2.9.0 final release. If you pick the ``tgz`` or ``zip`` distributions then just unzip it where you want it installed. If you pick the IzPack Installer then double click on it and follow the instructions.

You also need to make sure that the ``scala-2.9.0/bin`` (if that is the directory where you installed Scala) is on your ``PATH``::

    $ export PATH=$PATH:scala-2.9.0/bin

Now you can test you installation by invoking and see the printout::

    $ scala -version
    Scala code runner version 2.9.0.final -- Copyright 2002-2011, LAMP/EPFL

Looks like we are all good. Finally let's create a source file ``Pi.scala`` for the tutorial and put it in the root of the Akka distribution in the ``tutorial`` directory (you have to create it first).


Downloading and installing SBT
------------------------------

SBT, short for Simple Build Tool is an excellent build system written in Scala. It the preferred way of building software in Scala. If you want to use SBT for this tutorial then follow the following instructions, if not you can skip this section.

To install SBT and create a project for this tutorial it is easiest to follow the instructions on `this page <http://code.google.com/p/simple-build-tool/wiki/Setup>`_. The preferred SBT version to install is ``0.7.6``.

If you have created an SBT project then create a source file for the tutorial and put it in ``src/main/scala/Pi.scala``.

Now we need to make our project an Akka project. You can add the dependencies manually, but the easiest way is to use Akka's SBT Plugin.

TODO: write up about Akka's SBT Plugin

Now you need to make SBT download all dependencies it needs. That is done by invoking::

    $ sbt update

SBT itself needs a whole bunch of dependencies but our project will only need one; ``akka-actor-1.1.jar``. SBT downloads that as well.

Creating the messages
---------------------

First we need to create the messages is that we want to have flowing in the system. Let's create three different messages:

- ``Calculate`` -- starts the calculation
- ``Work`` -- contains the work assignment
- ``Result`` -- contains the result from the worker's calculation

Messages sent to actors should always be immutable to avoid sharing mutable state. In scala we have 'case classes' which make excellent messages. So let's start by creating three messages as case classes.  We also create a common base trait for our messages (that we define as being ``sealed`` in order to prevent creating messages outside our control)::

    sealed trait PiMessage

    case object Calculate extends PiMessage

    case class Work(arg: Int, nrOfElements: Int) extends PiMessage

    case class Result(value: Double) extends PiMessage

Creating the worker
-------------------

Now we can create the worker actor.  This is done by mixing in the ``Actor`` trait and defining the ``receive`` method. The ``receive`` method defines our message handler. We expect it to be able to handle the ``Work`` message so we need to add a handler for this message::

    class Worker extends Actor {
      def receive = {
        case Work(arg, nrOfElements) =>
          self reply Result(calculatePiFor(arg, nrOfElements)) // perform the work
      }
    }

As you can see we have now created an ``Actor`` with a ``receive`` method that as a handler for the ``Work`` message. In this handler we invoke the ``calculatePiFor(..)`` method, wraps the result in a ``Result`` message and sends it back to the original sender using ``self.reply``. In Akka the sender reference is implicitly passed along with the message so that the receiver can always reply or store away the sender reference use.

The only thing missing in our ``Worker`` actor is the implementation on the ``calculatePiFor(..)`` method. There are many ways we can implement this algorithm in Scala, now let's try to balance functional programming with efficiency and use a tail recursive function::

    def calculatePiFor(arg: Int, nrOfElements: Int): Double = {
      val end = (arg + 1) * nrOfElements - 1

      @tailrec def doCalculatePiFor(cursor: Int, acc: Double): Double = {
        if (end == cursor) acc
        else doCalculatePiFor(cursor + 1, acc + (4 * math.pow(-1, cursor) / (2 * cursor + 1)))
      }

      doCalculatePiFor(arg * nrOfElements, 0.0D)
    }

Here we use the classic trick with a local nested method to make sure that the compiler can perform a tail call optimization. We can ensure that the compiler will be able to do that by annotate tail recursive function with ``@tailrec``, with this annotation the compiler will emit an error if it can optimize it. With this implementation the calculation is really fast.

Creating the master
-------------------

The master actor is a little bit more involved. In its constructor we need to create the workers (the ``Worker`` actors) and start them. We will also wrap them in a load-balancing router to make it easier to spread out the work evenly between the workers. Let's do that first::

    // create the workers
    val workers = Vector.fill(nrOfWorkers)(actorOf[Worker].start)

    // wrap them with a load-balancing router
    val router = Routing.loadBalancerActor(CyclicIterator(workers)).start

As you can see we are using the ``actorOf`` factory method to create actors, this method returns as an ``ActorRef`` which is a reference to our newly created actor.  This method is available in the ``Actor`` object but is usually imported::

    import akka.actor.Actor._

Now we have a routes are representing all our workers in a single abstraction. If you paid attention to the code above to see that we were using the ``nrOfWorkers`` variable. This variable and others we have to pass to the ``Master`` actor in its constructor. So now let's create the master actor. We had to pass in three integer variables needed:

- ``nrOfWorkers`` -- defining how many workers we should start up
- ``nrOfMessages`` -- defining how many numebr chunks should send out to the workers
- ``nrOfElements`` -- defining how big the number chunks sent to each worker should be

Let's not write the master actor::

    class Master(nrOfWorkers: Int, nrOfMessages: Int, nrOfElements: Int, latch: CountDownLatch)
      extends Actor {

      var pi: Double = _
      var nrOfResults: Int = _
      var start: Long = _

      // create the workers
      val workers = Vector.fill(nrOfWorkers)(actorOf[Worker].start)

      // wrap them with a load-balancing router
      val router = Routing.loadBalancerActor(CyclicIterator(workers)).start

      def receive = { ... }

      override def preStart = start = now

      override def postStop = {
        // tell the world that the calculation is complete
        println(
          "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis"
          .format(pi, (now - start)))
        latch.countDown
      }
    }

Couple of things are worth explaining further.

First, we are passing in a ``java.util.concurrent.CountDownLatch`` to the ``Master`` actor. This latch is only used for plumbing, to have a simple way letting the outside world knowing when the master can deliver the result and shut down. In more idiomatic Akka code, as we will see in part two of this tutorial series, we would not use a latch.

Second, we are adding a couple of lifecycle callback methods; ``preStart`` and ``postStop``. In the ``preStart`` callback we are recording the time when the actor is started and in the ``postStop`` callback we are printing out the result (the approximation of Pi) and the time it took to calculate it. In this call but we also invoke ``latch.countDown`` to tell the outside world that we are done.

But we are not done yet. We are missing the message handler for the ``Master`` actor. This message handler needs to be able to react to two different messages:

- ``Calculate`` -- which should start the calculation
- ``Result`` -- which should aggregate the different results

The ``Calculate`` handler is sending out work to all the ``Worker`` actors and after doing that also sends a ``Broadcast(PoisonPill)`` message to the router, this will make the route or send out the ``PoisonPill`` message to all the actors in this representing (in our case all the ``Worker`` actors). The ``PoisonPill`` is a special kind of message that tells the receiver to shut himself down using the normal shutdown; ``self.stop``. Then we also send a ``PoisonPill`` to the router itself (since it's also an actor that we want to shut down).

The ``Result`` handler is simpler, here we just get the value  from the ``Result`` message and aggregate it to our ``pi`` member variable. We also keep track of how many results we have received back and if it matches the number of tasks sent out the ``Master`` actor considers itself done and shuts himself down.

Now, let's capture this in code::

    // message handler
    def receive = {
      case Calculate =>
        // schedule work
        for (arg <- 0 until nrOfMessages) router ! Work(arg, nrOfElements)

        // send a PoisonPill to all workers telling them to shut down themselves
        router ! Broadcast(PoisonPill)

        // send a PoisonPill to the router, telling him to shut himself down
        router ! PoisonPill

      case Result(value) =>
        // handle result from the worker
        pi += value
        nrOfResults += 1
        if (nrOfResults == nrOfMessages) self.stop
    }

Bootstrap the calculation
-------------------------

Now the only thing that is left to implement his the runner that should bootstrap and run his calculation for us. We do that by creating an object that we call ``Pi``, here we can extend the ``App`` trait in Scala which means that we will be able to run this as an application directly from the command line. The ``Pi`` object is a perfect container module for our actors and messages, so let's put them all there. We also create a method ``calculate`` in which we start up the ``Master`` actor and waits for it to finish::

    object Pi extends App {

      calculate(nrOfWorkers = 4, nrOfElements = 10000, nrOfMessages = 10000)

      ... // actors and messages

      def calculate(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) {

        // this latch is only plumbing to know when the calculation is completed
        val latch = new CountDownLatch(1)

        // create the master
        val master = actorOf(new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch)).start

        // start the calculation
        master ! Calculate

        // wait for master to shut down
        latch.await
      }
    }

That's it. Now we are done.

But before we package it up and run it, let's take a look at the full code now, with package declaration, imports and all of::

    package akka.tutorial.scala.first

    import akka.actor.{Actor, ActorRef, PoisonPill}
    import Actor._
    import akka.routing.{Routing, CyclicIterator}
    import Routing._
    import akka.dispatch.Dispatchers

    import System.{currentTimeMillis => now}
    import java.util.concurrent.CountDownLatch

    import scala.annotation.tailrec

    object Pi extends App {

      calculate(nrOfWorkers = 4, nrOfElements = 10000, nrOfMessages = 10000)

      // ====================
      // ===== Messages =====
      // ====================
      sealed trait PiMessage
      case object Calculate extends PiMessage
      case class Work(arg: Int, nrOfElements: Int) extends PiMessage
      case class Result(value: Double) extends PiMessage

      // ==================
      // ===== Worker =====
      // ==================
      class Worker extends Actor {

        // define the work
        def calculatePiFor(arg: Int, nrOfElements: Int): Double = {
          val end = (arg + 1) * nrOfElements - 1
          @tailrec def doCalculatePiFor(cursor: Int, acc: Double): Double = {
            if (end == cursor) acc
            else doCalculatePiFor(cursor + 1, acc + (4 * math.pow(-1, cursor) / (2 * cursor + 1)))
          }
          doCalculatePiFor(arg * nrOfElements, 0.0D)
        }

        def receive = {
          case Work(arg, nrOfElements) =>
            self reply Result(calculatePiFor(arg, nrOfElements)) // perform the work
        }
      }

      // ==================
      // ===== Master =====
      // ==================
      class Master(nrOfWorkers: Int, nrOfMessages: Int, nrOfElements: Int, latch: CountDownLatch)
        extends Actor {

        var pi: Double = _
        var nrOfResults: Int = _
        var start: Long = _

        // create the workers
        val workers = Vector.fill(nrOfWorkers)(actorOf[Worker].start)

        // wrap them with a load-balancing router
        val router = Routing.loadBalancerActor(CyclicIterator(workers)).start

        // message handler
        def receive = {
          case Calculate =>
            // schedule work
            for (arg <- 0 until nrOfMessages) router ! Work(arg, nrOfElements)

            // send a PoisonPill to all workers telling them to shut down themselves
            router ! Broadcast(PoisonPill)

            // send a PoisonPill to the router, telling him to shut himself down
            router ! PoisonPill

          case Result(value) =>
            // handle result from the worker
            pi += value
            nrOfResults += 1
            if (nrOfResults == nrOfMessages) self.stop
        }

        override def preStart = start = now

        override def postStop = {
          // tell the world that the calculation is complete
          println(
            "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis"
            .format(pi, (now - start)))
          latch.countDown
        }
      }

      // ==================
      // ===== Run it =====
      // ==================
      def calculate(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) {

        // this latch is only plumbing to know when the calculation is completed
        val latch = new CountDownLatch(1)

        // create the master
        val master = actorOf(new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch)).start

        // start the calculation
        master ! Calculate

        // wait for master to shut down
        latch.await
      }
    }

Run it as a command line application
------------------------------------

If you have not typed (or copied) in the code for the tutorial in the ``$AKKA_HOME/tutorial/Pi.scala`` then now is the time. When that is done open up a shell and step in to the Akka distribution (``cd $AKKA_HOME``).

First we need to compile the source file. That is done with Scala's compiler ``scalac``. Our application depends on the ``akka-actor-1.1.jar`` JAR file, so let's add that to the compiler classpath when we compile the source::

    $ scalac -cp dist/akka-actor-1.1.jar tutorial/Pi.scala

When we have compiled the source file we are ready to run the application. This is done with ``java`` but yet again we need to add the ``akka-actor-1.1.jar`` JAR file to the classpath, this time we also need to add the Scala runtime library ``scala-library.jar`` and the classes we compiled ourselves to the classpath::

    $ java -cp dist/akka-actor-1.1.jar:scala-library.jar:tutorial akka.tutorial.scala.first.Pi
    AKKA_HOME is defined as [/Users/jboner/src/akka-stuff/akka-core], loading config from \
      [/Users/jboner/src/akka-stuff/akka-core/config/akka.conf].

    Pi estimate:        3.1435501812459323
    Calculation time:   858 millis

Yipee! It is working.

Run it inside SBT
-----------------

If you have based the tutorial on SBT then you can run the application directly inside SBT. First you need to compile the project::

    $ sbt
    > update
    ...
    > compile
    ...

When this in done we can start up a Scala REPL (console/interpreter) directly inside SBT with our dependencies and classes on the classpath::

    > console
    ...
    scala>

In this REPL we can now evaluate Scala code. For example run our application::

    scala> akka.tutorial.scala.first.Pi.calculate(
         | nrOfWorkers = 4, nrOfElements = 10000, nrOfMessages = 10000)
    AKKA_HOME is defined as [/Users/jboner/src/akka-stuff/akka-core], loading config from \
      [/Users/jboner/src/akka-stuff/akka-core/config/akka.conf].

    Pi estimate:        3.1435501812459323
    Calculation time:   942 millis

See it complete the calculation and print out the result. When that is done we can exit the REPL::

    > :quit

Yipee! It is working.

Conclusion
----------

TODO