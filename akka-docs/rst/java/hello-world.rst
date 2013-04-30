##########################
The Obligatory Hello World
##########################

Since every programming paradigm needs to solve the tough problem of printing a
well-known greeting to the console we’ll introduce you to the actor-based
version.

.. includecode:: ../java/code/docs/actor/japi/HelloWorld.java#hello-world

The ``HelloWorld`` actor is the application’s “main” class; when it terminates
the application will shut down—more on that later. The main business logic
happens in the :meth:`preStart` method, where a ``Greeter`` actor is created
and instructed to issue that greeting we crave for. When the greeter is done it
will tell us so by sending back a message, and when that message has been
received it will be passed into the :meth:`onReceive` method where we can
conclude the demonstration by stopping the ``HelloWorld`` actor. You will be
very curious to see how the ``Greeter`` actor performs the actual task:

.. includecode:: ../java/code/docs/actor/japi/Greeter.java#greeter

This is extremely simple now: after its creation this actor will not do
anything until someone sends it a message, and if that happens to be an
invitation to greet the world then the ``Greeter`` complies and informs the
requester that the deed has been done.

As a Java developer you will probably want to tell us that there is no
``static public void main(...)`` anywhere in these classes, so how do we run
this program? The answer is that the appropriate :meth:`main` method is
implemented in the generic launcher class :class:`akka.Main` which expects only
one command line argument: the class name of the application’s main actor. This
main method will then create the infrastructure needed for running the actors,
start the given main actor and arrange for the whole application to shut down
once the main actor terminates. Thus you will be able to run the above code
with a command similar to the following::

  java -classpath <all those JARs> akka.Main com.example.HelloWorld

This conveniently assumes placement of the above class definitions in package
``com.example`` and it further assumes that you have the required JAR files for
``scala-library`` and ``akka-actor`` available. The easiest would be to manage
these dependencies with a build tool, see :ref:`build-tool`.

