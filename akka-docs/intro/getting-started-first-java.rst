.. _getting-started-first-java:

Getting Started Tutorial (Java): First Chapter
==============================================

Introduction
------------

Welcome to the first tutorial on how to get started with `Akka <http://akka.io>`_ and Java. We assume that you already know what Akka and Java are and will now focus on the steps necessary to start your first project.

There are two variations of this first tutorial:

- creating a standalone project and run it from the command line
- creating a Maven project and running it from within Maven

Since they are so similar we will present them both.

The sample application that we will create is using actors to calculate the value of Pi. Calculating Pi is a CPU intensive operation and we will utilize Akka Actors to write a concurrent solution that scales out to multi-core processors. This sample will be extended in future tutorials to use Akka Remote Actors to scale out on multiple machines in a cluster.

We will be using an algorithm that is called "embarrassingly parallel" which just means that each job is completely isolated and not coupled with any other job. Since this algorithm is so parallelizable it suits the actor model very well.

Here is the formula for the algorithm we will use:

.. image:: ../images/pi-formula.png

In this particular algorithm the master splits the series into chunks which are sent out to each worker actor to be processed. When each worker has processed its chunk it sends a result back to the master which aggregates the total result.

Tutorial source code
--------------------

If you want don't want to type in the code and/or set up a Maven project then you can check out the full tutorial from the Akka GitHub repository. It is in the ``akka-tutorials/akka-tutorial-first`` module. You can also browse it online `here`__, with the actual source code `here`__.

__ https://github.com/jboner/akka/tree/master/akka-tutorials/akka-tutorial-first
__ https://github.com/jboner/akka/blob/master/akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java

To check out the code using Git invoke the following::

    $ git clone git://github.com/jboner/akka.git

Then you can navigate down to the tutorial::

    $ cd akka/akka-tutorials/akka-tutorial-first

Prerequisites
-------------

This tutorial assumes that you have Java 1.6 or later installed on you machine and ``java`` on your ``PATH``. You also need to know how to run commands in a shell (ZSH, Bash, DOS etc.) and a decent text editor or IDE to type in the Java code.

You need to make sure that ``$JAVA_HOME`` environment variable is set to the root of the Java distribution. You also need to make sure that the ``$JAVA_HOME/bin`` is on your ``PATH``::

    $ export JAVA_HOME=..root of java distribution..
    $ export PATH=$PATH:$JAVA_HOME/bin

You can test your installation by invoking ``java``::

    $ java -version
    java version "1.6.0_24"
    Java(TM) SE Runtime Environment (build 1.6.0_24-b07-334-10M3326)
    Java HotSpot(TM) 64-Bit Server VM (build 19.1-b02-334, mixed mode)


Downloading and installing Akka
-------------------------------

To build and run the tutorial sample from the command line, you have to download
Akka. If you prefer to use SBT to build and run the sample then you can skip
this section and jump to the next one.

Let's get the ``akka-actors-1.3.1.zip`` distribution of Akka from
http://akka.io/downloads/ which includes everything we need for this
tutorial. Once you have downloaded the distribution unzip it in the folder you
would like to have Akka installed in. In my case I choose to install it in
``/Users/jboner/tools/``, simply by unzipping it to this directory.

You need to do one more thing in order to install Akka properly: set the
``AKKA_HOME`` environment variable to the root of the distribution. In my case
I'm opening up a shell, navigating down to the distribution, and setting the
``AKKA_HOME`` variable::

    $ cd /Users/jboner/tools/akka-actors-1.3.1
    $ export AKKA_HOME=`pwd`
    $ echo $AKKA_HOME
    /Users/jboner/tools/akka-actors-1.3.1

The distribution looks like this::

    $ ls -1
    config
    doc
    lib
    src

- In the ``config`` directory we have the Akka conf files.
- In the ``doc`` directory we have the documentation, API, doc JARs, and also
  the source files for the tutorials.
- In the ``lib`` directory we have the Scala and Akka JARs.
- In the ``src`` directory we have the source JARs for Akka.


The only JAR we will need for this tutorial (apart from the
``scala-library.jar`` JAR) is the ``akka-actor-1.3.1.jar`` JAR in the ``lib/akka``
directory. This is a self-contained JAR with zero dependencies and contains
everything we need to write a system using Actors.

Akka is very modular and has many JARs for containing different features. The core distribution has seven modules:

- ``akka-actor-1.3.1.jar`` -- Standard Actors
- ``akka-typed-actor-1.3.1.jar`` -- Typed Actors
- ``akka-remote-1.3.1.jar`` -- Remote Actors
- ``akka-stm-1.3.1.jar`` -- STM (Software Transactional Memory), transactors and transactional datastructures
- ``akka-http-1.3.1.jar`` -- Akka Mist for continuation-based asynchronous HTTP and also Jersey integration
- ``akka-slf4j-1.3.1.jar`` -- SLF4J Event Handler Listener for logging with SLF4J
- ``akka-testkit-1.3.1.jar`` -- Toolkit for testing Actors

We also have Akka Modules containing add-on modules outside the core of
Akka. You can download the Akka Modules distribution from `<http://akka.io/downloads/>`_. It contains Akka
core as well. We will not be needing any modules there today, but for your
information the module JARs are these:

- ``akka-kernel-1.3.1.jar`` -- Akka microkernel for running a bare-bones mini application server (embeds Jetty etc.)
- ``akka-amqp-1.3.1.jar`` -- AMQP integration
- ``akka-camel-1.3.1.jar`` -- Apache Camel Actors integration (it's the best way to have your Akka application communicate with the rest of the world)
- ``akka-camel-typed-1.3.1.jar`` -- Apache Camel Typed Actors integration
- ``akka-scalaz-1.3.1.jar`` -- Support for the Scalaz library
- ``akka-spring-1.3.1.jar`` -- Spring framework integration
- ``akka-osgi-dependencies-bundle-1.3.1.jar`` -- OSGi support


Downloading and installing Maven
--------------------------------

Maven is an excellent build system that can be used to build both Java and Scala projects. If you want to use Maven for this tutorial then follow the following instructions, if not you can skip this section and the next.

First browse to `http://maven.apache.org/download.html <http://maven.apache.org/download.html>`_ and download the ``3.0.3`` distribution.

To install Maven it is easiest to follow the instructions on `http://maven.apache.org/download.html#Installation <http://maven.apache.org/download.html#Installation>`_.

Creating an Akka Maven project
------------------------------

If you have not already done so, now is the time to create a Maven project for our tutorial. You do that by stepping into the directory you want to create your project in and invoking the ``mvn`` command::

    $ mvn archetype:generate \
        -DgroupId=akka.tutorial.first.java \
        -DartifactId=akka-tutorial-first-java \
        -DarchetypeArtifactId=maven-archetype-quickstart \
        -DinteractiveMode=false

Now we have the basis for our Maven-based Akka project. Let's step into the project directory::

    $ cd akka-tutorial-first-java

Here is the layout that Maven created::

    akka-tutorial-first-jboner
    |-- pom.xml
    `-- src
        |-- main
        |   `-- java
        |       `-- akka
        |           `-- tutorial
        |               `-- first
        |                   `-- java
        |                       `-- App.java

As you can see we already have a Java source file called ``App.java``, let's now rename it to ``Pi.java``.

We also need to edit the ``pom.xml`` build file. Let's add the dependency we need as well as the Maven repository it should download it from. The Akka Maven repository can be found at `<http://akka.io/repository>`_ 
and Typesafe provides `<http://repo.typesafe.com/typesafe/releases/>`_ that proxies several other repositories, including akka.io. 
It should now look something like this:

.. code-block:: xml

    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>

        <name>akka-tutorial-first-java</name>
        <groupId>akka.tutorial.first.java</groupId>
        <artifactId>akka-tutorial-first-java</artifactId>
        <packaging>jar</packaging>
        <version>1.0-SNAPSHOT</version>
        <url>http://akka.io</url>

        <dependencies>
            <dependency>
                <groupId>se.scalablesolutions.akka</groupId>
                <artifactId>akka-actor</artifactId>
                <version>1.3.1</version>
            </dependency>
        </dependencies>

        <repositories>
            <repository>
                <id>typesafe</id>
                <name>Typesafe Repository</name>
                <url>http://repo.typesafe.com/typesafe/releases/</url>
            </repository>
        </repositories>

        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>2.3.2</version>
                    <configuration>
                        <source>1.6</source>
                        <target>1.6</target>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </project>

Start writing the code
----------------------

Now it's about time to start hacking.

We start by creating a ``Pi.java`` file and adding these import statements at the top of the file::

    package akka.tutorial.first.java;

    import static akka.actor.Actors.actorOf;
    import static akka.actor.Actors.poisonPill;
    import static java.util.Arrays.asList;

    import akka.actor.ActorRef;
    import akka.actor.UntypedActor;
    import akka.actor.UntypedActorFactory;
    import akka.routing.CyclicIterator;
    import akka.routing.InfiniteIterator;
    import akka.routing.Routing.Broadcast;
    import akka.routing.UntypedLoadBalancer;

    import java.util.concurrent.CountDownLatch;

If you are using Maven in this tutorial then create the file in the ``src/main/java/akka/tutorial/first/java`` directory.

If you are using the command line tools then create the file wherever you want. I will create it in a directory called ``tutorial`` at the root of the Akka distribution, e.g. in ``$AKKA_HOME/tutorial/akka/tutorial/first/java/Pi.java``.

Creating the messages
---------------------

The design we are aiming for is to have one ``Master`` actor initiating the computation, creating a set of ``Worker`` actors. Then it splits up the work into discrete chunks, and sends these chunks to the different workers in a round-robin fashion. The master waits until all the workers have completed their work and sent back results for aggregation. When computation is completed the master prints out the result, shuts down all workers and then itself.

With this in mind, let's now create the messages that we want to have flowing in the system. We need three different messages:

- ``Calculate`` -- sent to the ``Master`` actor to start the calculation
- ``Work`` -- sent from the ``Master`` actor to the ``Worker`` actors containing the work assignment
- ``Result`` -- sent from the ``Worker`` actors to the ``Master`` actor containing the result from the worker's calculation

Messages sent to actors should always be immutable to avoid sharing mutable state. So let's start by creating three messages as immutable POJOs. We also create a wrapper ``Pi`` class to hold our implementation::

    public class Pi {

      static class Calculate {}

      static class Work {
        private final int start;
        private final int nrOfElements;

        public Work(int start, int nrOfElements) {
          this.start = start;
          this.nrOfElements = nrOfElements;
        }

        public int getStart() { return start; }
        public int getNrOfElements() { return nrOfElements; }
      }

      static class Result {
        private final double value;

        public Result(double value) {
          this.value = value;
        }

        public double getValue() { return value; }
      }
    }

Creating the worker
-------------------

Now we can create the worker actor.  This is done by extending in the ``UntypedActor`` base class and defining the ``onReceive`` method. The ``onReceive`` method defines our message handler. We expect it to be able to handle the ``Work`` message so we need to add a handler for this message::

    static class Worker extends UntypedActor {

      // message handler
      public void onReceive(Object message) {
        if (message instanceof Work) {
          Work work = (Work) message;

          // perform the work
          double result = calculatePiFor(work.getStart(), work.getNrOfElements());

          // reply with the result
          getContext().replyUnsafe(new Result(result));

        } else throw new IllegalArgumentException("Unknown message [" + message + "]");
      }
    }

As you can see we have now created an ``UntypedActor`` with a ``onReceive`` method as a handler for the ``Work`` message. In this handler we invoke the ``calculatePiFor(..)`` method, wrap the result in a ``Result`` message and send it back to the original sender using ``getContext().replyUnsafe(..)``. In Akka the sender reference is implicitly passed along with the message so that the receiver can always reply or store away the sender reference for future use.

The only thing missing in our ``Worker`` actor is the implementation on the ``calculatePiFor(..)`` method::

    // define the work
    private double calculatePiFor(int start, int nrOfElements) {
      double acc = 0.0;
      for (int i = start * nrOfElements; i <= ((start + 1) * nrOfElements - 1); i++) {
        acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1);
      }
      return acc;
    }

Creating the master
-------------------

The master actor is a little bit more involved. In its constructor we need to create the workers (the ``Worker`` actors) and start them. We will also wrap them in a load-balancing router to make it easier to spread out the work evenly between the workers. Let's do that first::

    static class Master extends UntypedActor {
      ...

      static class PiRouter extends UntypedLoadBalancer {
        private final InfiniteIterator<ActorRef> workers;

        public PiRouter(ActorRef[] workers) {
          this.workers = new CyclicIterator<ActorRef>(asList(workers));
        }

        public InfiniteIterator<ActorRef> seq() {
          return workers;
        }
      }

      public Master(...) {
        ...

        // create the workers
        final ActorRef[] workers = new ActorRef[nrOfWorkers];
        for (int i = 0; i < nrOfWorkers; i++) {
          workers[i] = actorOf(Worker.class).start();
        }

        // wrap them with a load-balancing router
        ActorRef router = actorOf(new UntypedActorFactory() {
          public UntypedActor create() {
            return new PiRouter(workers);
          }
        }).start();
      }
    }

As you can see we are using the ``actorOf`` factory method to create actors, this method returns as an ``ActorRef`` which is a reference to our newly created actor.  This method is available in the ``Actors`` object but is usually imported::

    import static akka.actor.Actors.actorOf;

One thing to note is that we used two different versions of the ``actorOf`` method. For creating the ``Worker`` actor we just pass in the class but to create the ``PiRouter`` actor we can't do that since the constructor in the ``PiRouter`` class takes arguments, instead we need to use the ``UntypedActorFactory`` which unfortunately is a bit more verbose.

``actorOf`` is the only way to create an instance of an Actor, this is enforced by Akka runtime. The ``actorOf`` method instantiates the actor and returns, not an instance to the actor, but an instance to an ``ActorRef``. This reference is the handle through which you communicate with the actor. It is immutable, serializable and location-aware meaning that it "remembers" its original actor even if it is sent to other nodes across the network and can be seen as the equivalent to the Erlang actor's PID.

The actor's life-cycle is:

- Created -- ``Actor.actorOf[MyActor]`` -- can **not** receive messages
- Started -- ``actorRef.start()`` -- can receive messages
- Stopped -- ``actorRef.stop()`` -- can **not** receive messages

Once the actor has been stopped it is dead and can not be started again.

Now we have a router that is representing all our workers in a single abstraction. If you paid attention to the code above, you saw that we were using the ``nrOfWorkers`` variable. This variable and others we have to pass to the ``Master`` actor in its constructor. So now let's create the master actor. We have to pass in three integer variables:

- ``nrOfWorkers`` -- defining how many workers we should start up
- ``nrOfMessages`` -- defining how many number chunks to send out to the workers
- ``nrOfElements`` -- defining how big the number chunks sent to each worker should be

Here is the master actor::

    static class Master extends UntypedActor {
      private final int nrOfMessages;
      private final int nrOfElements;
      private final CountDownLatch latch;

      private double pi;
      private int nrOfResults;
      private long start;

      private ActorRef router;

      static class PiRouter extends UntypedLoadBalancer {
        private final InfiniteIterator<ActorRef> workers;

        public PiRouter(ActorRef[] workers) {
          this.workers = new CyclicIterator<ActorRef>(asList(workers));
        }

        public InfiniteIterator<ActorRef> seq() {
          return workers;
        }
      }

      public Master(
        int nrOfWorkers, int nrOfMessages, int nrOfElements, CountDownLatch latch) {
        this.nrOfMessages = nrOfMessages;
        this.nrOfElements = nrOfElements;
        this.latch = latch;

        // create the workers
        final ActorRef[] workers = new ActorRef[nrOfWorkers];
        for (int i = 0; i < nrOfWorkers; i++) {
          workers[i] = actorOf(Worker.class).start();
        }

        // wrap them with a load-balancing router
        router = actorOf(new UntypedActorFactory() {
          public UntypedActor create() {
            return new PiRouter(workers);
          }
        }).start();
      }

      // message handler
      public void onReceive(Object message) { ... }

      @Override
      public void preStart() {
        start = System.currentTimeMillis();
      }

      @Override
      public void postStop() {
        // tell the world that the calculation is complete
         System.out.println(String.format(
           "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis",
           pi, (System.currentTimeMillis() - start)));
        latch.countDown();
      }
    }

A couple of things are worth explaining further.

First, we are passing in a ``java.util.concurrent.CountDownLatch`` to the ``Master`` actor. This latch is only used for plumbing (in this specific tutorial), to have a simple way of letting the outside world knowing when the master can deliver the result and shut down. In more idiomatic Akka code, as we will see in part two of this tutorial series, we would not use a latch but other abstractions and functions like ``Channel``, ``Future`` and ``sendRequestReplyFuture`` to achieve the same thing in a non-blocking way. But for simplicity let's stick to a ``CountDownLatch`` for now.

Second, we are adding a couple of life-cycle callback methods; ``preStart`` and ``postStop``. In the ``preStart`` callback we are recording the time when the actor is started and in the ``postStop`` callback we are printing out the result (the approximation of Pi) and the time it took to calculate it. In this call we also invoke ``latch.countDown()`` to tell the outside world that we are done.

But we are not done yet. We are missing the message handler for the ``Master`` actor. This message handler needs to be able to react to two different messages:

- ``Calculate`` -- which should start the calculation
- ``Result`` -- which should aggregate the different results

The ``Calculate`` handler is sending out work to all the ``Worker`` actors and after doing that it also sends a ``new Broadcast(poisonPill())`` message to the router, which will send out the ``PoisonPill`` message to all the actors it is representing (in our case all the ``Worker`` actors). ``PoisonPill`` is a special kind of message that tells the receiver to shut itself down using the normal shutdown method; ``getContext().stop()``, and is created through the ``poisonPill()`` method. We also send a ``PoisonPill`` to the router itself (since it's also an actor that we want to shut down).

The ``Result`` handler is simpler, here we get the value from the ``Result`` message and aggregate it to our ``pi`` member variable. We also keep track of how many results we have received back, and if that matches the number of tasks sent out, the ``Master`` actor considers itself done and shuts down.

Let's capture this in code::

    // message handler
    public void onReceive(Object message) {

      if (message instanceof Calculate) {
        // schedule work
        for (int start = 0; start < nrOfMessages; start++) {
          router.tell(new Work(start, nrOfElements), getContext());
        }

        // send a PoisonPill to all workers telling them to shut down themselves
        router.tell(new Broadcast(poisonPill()));

        // send a PoisonPill to the router, telling him to shut himself down
        router.tell(poisonPill());

      } else if (message instanceof Result) {

        // handle result from the worker
        Result result = (Result) message;
        pi += result.getValue();
        nrOfResults += 1;
        if (nrOfResults == nrOfMessages) getContext().stop();

      } else throw new IllegalArgumentException("Unknown message [" + message + "]");
    }

Bootstrap the calculation
-------------------------

Now the only thing that is left to implement is the runner that should bootstrap and run the calculation for us. We do that by adding a ``main`` method to the enclosing ``Pi`` class in which we create a new instance of ``Pi`` and invoke method ``calculate`` in which we start up the ``Master`` actor and wait for it to finish::

    public class Pi {

      public static void main(String[] args) throws Exception {
        Pi pi = new Pi();
        pi.calculate(4, 10000, 10000);
      }

      public void calculate(final int nrOfWorkers, final int nrOfElements, final int nrOfMessages)
        throws Exception {

        // this latch is only plumbing to know when the calculation is completed
        final CountDownLatch latch = new CountDownLatch(1);

        // create the master
        ActorRef master = actorOf(new UntypedActorFactory() {
          public UntypedActor create() {
            return new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch);
          }
        }).start();

        // start the calculation
        master.tell(new Calculate());

        // wait for master to shut down
        latch.await();
      }
    }

That's it. Now we are done.

Before we package it up and run it, let's take a look at the full code now, with package declaration, imports and all::

    package akka.tutorial.first.java;

    import static akka.actor.Actors.actorOf;
    import static akka.actor.Actors.poisonPill;
    import static java.util.Arrays.asList;

    import akka.actor.ActorRef;
    import akka.actor.UntypedActor;
    import akka.actor.UntypedActorFactory;
    import akka.routing.CyclicIterator;
    import akka.routing.InfiniteIterator;
    import akka.routing.Routing.Broadcast;
    import akka.routing.UntypedLoadBalancer;

    import java.util.concurrent.CountDownLatch;

    public class Pi {

      public static void main(String[] args) throws Exception {
        Pi pi = new Pi();
        pi.calculate(4, 10000, 10000);
      }

      // ====================
      // ===== Messages =====
      // ====================
      static class Calculate {}

      static class Work {
        private final int start;
        private final int nrOfElements;

        public Work(int start, int nrOfElements) {
          this.start = start;
          this.nrOfElements = nrOfElements;
        }

        public int getStart() { return start; }
        public int getNrOfElements() { return nrOfElements; }
      }

      static class Result {
        private final double value;

        public Result(double value) {
          this.value = value;
        }

        public double getValue() { return value; }
      }

      // ==================
      // ===== Worker =====
      // ==================
      static class Worker extends UntypedActor {

        // define the work
        private double calculatePiFor(int start, int nrOfElements) {
          double acc = 0.0;
          for (int i = start * nrOfElements; i <= ((start + 1) * nrOfElements - 1); i++) {
            acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1);
          }
          return acc;
        }

        // message handler
        public void onReceive(Object message) {
          if (message instanceof Work) {
            Work work = (Work) message;

            // perform the work
            double result = calculatePiFor(work.getStart(), work.getNrOfElements())

            // reply with the result
            getContext().replyUnsafe(new Result(result));

          } else throw new IllegalArgumentException("Unknown message [" + message + "]");
        }
      }

      // ==================
      // ===== Master =====
      // ==================
      static class Master extends UntypedActor {
        private final int nrOfMessages;
        private final int nrOfElements;
        private final CountDownLatch latch;

        private double pi;
        private int nrOfResults;
        private long start;

        private ActorRef router;

        static class PiRouter extends UntypedLoadBalancer {
          private final InfiniteIterator<ActorRef> workers;

          public PiRouter(ActorRef[] workers) {
            this.workers = new CyclicIterator<ActorRef>(asList(workers));
          }

          public InfiniteIterator<ActorRef> seq() {
            return workers;
          }
        }

        public Master(
          int nrOfWorkers, int nrOfMessages, int nrOfElements, CountDownLatch latch) {

          this.nrOfMessages = nrOfMessages;
          this.nrOfElements = nrOfElements;
          this.latch = latch;

          // create the workers
          final ActorRef[] workers = new ActorRef[nrOfWorkers];
          for (int i = 0; i < nrOfWorkers; i++) {
            workers[i] = actorOf(Worker.class).start();
          }

          // wrap them with a load-balancing router
          router = actorOf(new UntypedActorFactory() {
            public UntypedActor create() {
              return new PiRouter(workers);
            }
          }).start();
        }

        // message handler
        public void onReceive(Object message) {

          if (message instanceof Calculate) {
            // schedule work
            for (int start = 0; start < nrOfMessages; start++) {
              router.tell(new Work(start, nrOfElements), getContext());
            }

            // send a PoisonPill to all workers telling them to shut down themselves
            router.tell(new Broadcast(poisonPill()));

            // send a PoisonPill to the router, telling him to shut himself down
            router.tell(poisonPill());

          } else if (message instanceof Result) {

            // handle result from the worker
            Result result = (Result) message;
            pi += result.getValue();
            nrOfResults += 1;
            if (nrOfResults == nrOfMessages) getContext().stop();

          } else throw new IllegalArgumentException("Unknown message [" + message + "]");
        }

        @Override
        public void preStart() {
          start = System.currentTimeMillis();
        }

        @Override
        public void postStop() {
          // tell the world that the calculation is complete
          System.out.println(String.format(
            "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis",
            pi, (System.currentTimeMillis() - start)));
          latch.countDown();
        }
      }

      // ==================
      // ===== Run it =====
      // ==================
      public void calculate(final int nrOfWorkers, final int nrOfElements, final int nrOfMessages)
        throws Exception {

        // this latch is only plumbing to know when the calculation is completed
        final CountDownLatch latch = new CountDownLatch(1);

        // create the master
        ActorRef master = actorOf(new UntypedActorFactory() {
          public UntypedActor create() {
            return new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch);
          }
        }).start();

        // start the calculation
        master.tell(new Calculate());

        // wait for master to shut down
        latch.await();
      }
    }


Run it as a command line application
------------------------------------

If you have not typed in (or copied) the code for the tutorial as
``$AKKA_HOME/tutorial/akka/tutorial/first/java/Pi.java`` then now is the
time. When that's done open up a shell and step in to the Akka distribution
(``cd $AKKA_HOME``).

First we need to compile the source file. That is done with Java's compiler
``javac``. Our application depends on the ``akka-actor-1.3.1.jar`` and the
``scala-library.jar`` JAR files, so let's add them to the compiler classpath
when we compile the source::

    $ javac -cp lib/scala-library.jar:lib/akka/akka-actor-1.3.1.jar tutorial/akka/tutorial/first/java/Pi.java

When we have compiled the source file we are ready to run the application. This
is done with ``java`` but yet again we need to add the ``akka-actor-1.3.1.jar``
and the ``scala-library.jar`` JAR files to the classpath as well as the classes
we compiled ourselves::

    $ java \
        -cp lib/scala-library.jar:lib/akka/akka-actor-1.3.1.jar:tutorial \
        akka.tutorial.java.first.Pi
    AKKA_HOME is defined as [/Users/jboner/tools/akka-actors-1.3.1]
    loading config from [/Users/jboner/tools/akka-actors-1.3.1/config/akka.conf].

    Pi estimate:        3.1435501812459323
    Calculation time:   822 millis

Yippee! It is working.

If you have not defined the ``AKKA_HOME`` environment variable then Akka can't
find the ``akka.conf`` configuration file and will print out a ``Can’t load
akka.conf`` warning. This is ok since it will then just use the defaults.


Run it inside Maven
-------------------

If you used Maven, then you can run the application directly inside Maven. First you need to compile the project::

    $ mvn compile

When this in done we can run our application directly inside Maven::

    $ mvn exec:java -Dexec.mainClass="akka.tutorial.first.java.Pi"
    ...
    Pi estimate:        3.1435501812459323
    Calculation time:   939 millis

Yippee! It is working.

If you have not defined an the ``AKKA_HOME`` environment variable then Akka can't find the ``akka.conf`` configuration file and will print out a ``Can’t load akka.conf`` warning. This is ok since it will then just use the defaults.

Conclusion
----------

We have learned how to create our first Akka project using Akka's actors to speed up a computation-intensive problem by scaling out on multi-core processors (also known as scaling up). We have also learned to compile and run an Akka project using either the tools on the command line or the SBT build system.

If you have a multi-core machine then I encourage you to try out different number of workers (number of working actors) by tweaking the ``nrOfWorkers`` variable to for example; 2, 4, 6, 8 etc. to see performance improvement by scaling up.

Now we are ready to take on more advanced problems. In the next tutorial we will build on this one, refactor it into more idiomatic Akka and Scala code, and introduce a few new concepts and abstractions. Whenever you feel ready, join me in the `Getting Started Tutorial: Second Chapter <TODO>`_.

Happy hakking.
