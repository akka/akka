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
Akka. If you prefer to use SBT to build and run the sample then you can skip this
section and jump to the next one.

Let's get the ``akka-2.0-RC1.zip`` distribution of Akka from
http://akka.io/downloads/ which includes everything we need for this
tutorial. Once you have downloaded the distribution unzip it in the folder you
would like to have Akka installed in. In my case I choose to install it in
``/Users/jboner/tools/``, simply by unzipping it to this directory.

You need to do one more thing in order to install Akka properly: set the
``AKKA_HOME`` environment variable to the root of the distribution. In my case
I'm opening up a shell, navigating down to the distribution, and setting the
``AKKA_HOME`` variable::

    $ cd /Users/jboner/tools/akka-2.0-RC1
    $ export AKKA_HOME=`pwd`
    $ echo $AKKA_HOME
    /Users/jboner/tools/akka-2.0-RC1

The distribution looks like this::

    $ ls -1
    bin
    config
    deploy
    doc
    lib
    src

- In the ``bin`` directory we have scripts for starting the Akka Microkernel.
- In the ``config`` directory we have the Akka conf files.
- In the ``deploy`` directory we can place applications to be run with the microkernel.
- In the ``doc`` directory we have the documentation, API, and doc JARs.
- In the ``lib`` directory we have the Scala and Akka JARs.
- In the ``src`` directory we have the source JARs for Akka.

The only JAR we will need for this tutorial (apart from the
``scala-library.jar`` JAR) is the ``akka-actor-2.0-RC1.jar`` JAR in the ``lib/akka``
directory. This is a self-contained JAR with zero dependencies and contains
everything we need to write a system using Actors.

Akka is very modular and has many JARs for containing different features. The
modules are:

- ``akka-actor`` -- Actors

- ``akka-remote`` -- Remote Actors

- ``akka-slf4j`` -- SLF4J Event Handler Listener for logging with SLF4J

- ``akka-testkit`` -- Toolkit for testing Actors

- ``akka-kernel`` -- Akka microkernel for running a bare-bones mini application server

- ``akka-durable-mailboxes`` -- Durable mailboxes: file-based, MongoDB, Redis, Beanstalk and Zookeeper

.. - ``akka-amqp`` -- AMQP integration
.. - ``akka-stm-2.0-RC1.jar`` -- STM (Software Transactional Memory), transactors and transactional datastructures
.. - ``akka-camel-2.0-RC1.jar`` -- Apache Camel Actors integration (it's the best way to have your Akka application communicate with the rest of the world)
.. - ``akka-camel-typed-2.0-RC1.jar`` -- Apache Camel Typed Actors integration
.. - ``akka-spring-2.0-RC1.jar`` -- Spring framework integration



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

We also need to edit the ``pom.xml`` build file. Let's add the dependency we
need as well as the Maven repository it should download it from. The Akka Maven
repository can be found at http://akka.io/releases/ and Typesafe provides
http://repo.typesafe.com/typesafe/releases/ that proxies several other
repositories, including akka.io.  It should now look something like this:

.. code-block:: xml

    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             http://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>

        <name>akka-tutorial-first-java</name>
        <groupId>akka.tutorial.first.java</groupId>
        <artifactId>akka-tutorial-first-java</artifactId>
        <packaging>jar</packaging>
        <version>1.0-SNAPSHOT</version>
        <url>http://akka.io</url>

        <dependencies>
            <dependency>
                <groupId>com.typesafe.akka</groupId>
                <artifactId>akka-actor</artifactId>
                <version>2.0-RC1</version>
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

We start by creating a ``Pi.java`` file and adding these import statements at the top of the file:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java#imports

If you are using Maven in this tutorial then create the file in the ``src/main/java/akka/tutorial/first/java`` directory.

If you are using the command line tools then create the file wherever you want.
We will create it in a directory called ``tutorial`` at the root of the Akka distribution,
e.g. in ``$AKKA_HOME/tutorial/akka/tutorial/first/java/Pi.java``.

Creating the messages
---------------------

The design we are aiming for is to have one ``Master`` actor initiating the
computation, creating a set of ``Worker`` actors. Then it splits up the work
into discrete chunks, and sends these chunks to the different workers in a
round-robin fashion. The master waits until all the workers have completed their
work and sent back results for aggregation. When computation is completed the
master sends the result to the ``Listener``, which prints out the result.

With this in mind, let's now create the messages that we want to have flowing in
the system. We need four different messages:

- ``Calculate`` -- sent to the ``Master`` actor to start the calculation
- ``Work`` -- sent from the ``Master`` actor to the ``Worker`` actors containing
  the work assignment
- ``Result`` -- sent from the ``Worker`` actors to the ``Master`` actor
  containing the result from the worker's calculation
- ``PiApproximation`` -- sent from the ``Master`` actor to the
  ``Listener`` actor containing the the final pi result and how long time
  the calculation took

Messages sent to actors should always be immutable to avoid sharing mutable state. So let's start by creating three messages as immutable POJOs. We also create a wrapper ``Pi`` class to hold our implementation:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java#messages

Creating the worker
-------------------

Now we can create the worker actor.  This is done by extending in the ``UntypedActor`` base class and defining the ``onReceive`` method. The ``onReceive`` method defines our message handler. We expect it to be able to handle the ``Work`` message so we need to add a handler for this message:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java#worker
   :exclude: calculatePiFor

As you can see we have now created an ``UntypedActor`` with a ``onReceive`` method as a handler for the ``Work`` message. In this handler we invoke the ``calculatePiFor(..)`` method, wrap the result in a ``Result`` message and send it back to the original sender using ``getContext().reply(..)``. In Akka the sender reference is implicitly passed along with the message so that the receiver can always reply or store away the sender reference for future use.

The only thing missing in our ``Worker`` actor is the implementation on the ``calculatePiFor(..)`` method:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java#calculatePiFor

Creating the master
-------------------

The master actor is a little bit more involved. In its constructor we create a round-robin router
to make it easier to spread out the work evenly between the workers. Let's do that first:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java#create-router

Now we have a router that is representing all our workers in a single
abstraction. So now let's create the master actor. We pass it three integer variables:

- ``nrOfWorkers`` -- defining how many workers we should start up
- ``nrOfMessages`` -- defining how many number chunks to send out to the workers
- ``nrOfElements`` -- defining how big the number chunks sent to each worker should be

Here is the master actor:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java#master
   :exclude: handle-messages

A couple of things are worth explaining further.

Note that we are passing in a ``ActorRef`` to the ``Master`` actor. This is used to
report the the final result to the outside world.

But we are not done yet. We are missing the message handler for the ``Master`` actor.
This message handler needs to be able to react to two different messages:

- ``Calculate`` -- which should start the calculation
- ``Result`` -- which should aggregate the different results

The ``Calculate`` handler is sending out work to all the ``Worker`` via its router.

The ``Result`` handler gets the value from the ``Result`` message and aggregates it to
our ``pi`` member variable. We also keep track of how many results we have received back,
and if that matches the number of tasks sent out, the ``Master`` actor considers itself done and
sends the final result to the ``listener``. When done it also invokes the ``getContext().stop(getSelf())``
method to stop itself *and* all its supervised actors.
In this case it has one supervised actor, the router, and this in turn has ``nrOfWorkers`` supervised actors.
All of them will be stopped automatically as the invocation of any supervisor's ``stop`` method
will propagate down to all its supervised 'children'.


Let's capture this in code:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java#master-receive

Creating the result listener
----------------------------

The listener is straightforward. When it receives the ``PiApproximation`` from the ``Master`` it
prints the result and shuts down the ``ActorSystem``.

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java#result-listener

Bootstrap the calculation
-------------------------

Now the only thing that is left to implement is the runner that should bootstrap and run the calculation for us.
We do that by adding a ``main`` method to the enclosing ``Pi`` class in which we create a new instance of ``Pi`` and
invoke method ``calculate`` in which we start up the ``Master`` actor and wait for it to finish:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java#app
   :exclude: actors-and-messages

As you can see the *calculate* method above it creates an ``ActorSystem`` and this is the Akka container which
will contain all actors created in that "context". An example of how to create actors in the container
is the *'system.actorOf(...)'* line in the calculate method. In this case we create two top level actors.
If you instead where in an actor context, i.e. inside an actor creating other actors, you should use
*getContext().actorOf(...)*. This is illustrated in the Master code above.

That's it. Now we are done.

Before we package it up and run it, let's take a look at the full code now, with package declaration, imports and all:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/java/akka/tutorial/first/java/Pi.java

Run it as a command line application
------------------------------------

If you have not typed in (or copied) the code for the tutorial as
``$AKKA_HOME/tutorial/akka/tutorial/first/java/Pi.java`` then now is the
time. When that's done open up a shell and step in to the Akka distribution
(``cd $AKKA_HOME``).

First we need to compile the source file. That is done with Java's compiler
``javac``. Our application depends on the ``akka-actor-2.0-RC1.jar`` and the
``scala-library.jar`` JAR files, so let's add them to the compiler classpath
when we compile the source::

    $ javac -cp lib/scala-library.jar:lib/akka/akka-actor-2.0-RC1.jar tutorial/akka/tutorial/first/java/Pi.java

When we have compiled the source file we are ready to run the application. This
is done with ``java`` but yet again we need to add the ``akka-actor-2.0-RC1.jar``
and the ``scala-library.jar`` JAR files to the classpath as well as the classes
we compiled ourselves::

    $ java \
        -cp lib/scala-library.jar:lib/akka/akka-actor-2.0-RC1.jar:. \
        akka.tutorial.java.first.Pi

    Pi approximation:   3.1435501812459323
    Calculation time:   359 millis

Yippee! It is working.


Run it inside Maven
-------------------

If you used Maven, then you can run the application directly inside Maven. First you need to compile the project::

    $ mvn compile

When this in done we can run our application directly inside Maven::

    $ mvn exec:java -Dexec.mainClass="akka.tutorial.first.java.Pi"
    ...
    Pi approximation:   3.1435501812459323
    Calculation time:   359 millis

Yippee! It is working.

Overriding Configuration Externally (Optional)
----------------------------------------------

The sample project includes an ``application.conf`` file in the resources directory:

.. includecode:: ../../akka-tutorials/akka-tutorial-first/src/main/resources/application.conf

If you uncomment the two lines, you should see a change in performance,
hopefully for the better (you might want to increase the number of messages in
the code to prolong the time the application runs). It should be noted that
overriding only works if a router type is given, so just uncommenting
``nr-of-instances`` does not work; see :ref:`routing-java` for more details.

.. note::

  Make sure that your ``application.conf`` is on the class path when you run
  the application. If running from inside Maven that should already be the
  case, otherwise you need to add the directory containing this file to the
  JVM’s ``-classpath`` option.

Conclusion
----------

We have learned how to create our first Akka project using Akka's actors to speed up a computation-intensive problem by scaling out on multi-core processors (also known as scaling up). We have also learned to compile and run an Akka project using either the tools on the command line or the SBT build system.

If you have a multi-core machine then I encourage you to try out different number of workers (number of working actors) by tweaking the ``nrOfWorkers`` variable to for example; 2, 4, 6, 8 etc. to see performance improvement by scaling up.

Happy hakking.
