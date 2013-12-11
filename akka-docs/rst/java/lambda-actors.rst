.. _lambda-actors-java:

###################################
 Actors (Java8 with Lambda Support)
###################################


Defining an Actor class
-----------------------

Actor classes are implemented by extending the :class:AbstractActor class and implementing
the :meth:`receive` method. The :meth:`receive` method should define a series of match
statements (which has the type ``PartialFunction<Object, BoxedUnit>``) that defines
which messages your Actor can handle, along with the implementation of how the
messages should be processed.

Don't let the type signature scare you. To allow you to easily build up a partial
function there is a builder named ``ReceiveBuilder`` that you can use.

Here is an example:

.. includecode:: ../../../akka-samples/akka-sample-java8/src/main/java/sample/java8/MyActor.java
   :include: imports,my-actor

Please note that the Akka Actor ``receive`` message loop is exhaustive, which
is different compared to Erlang and the late Scala Actors. This means that you
need to provide a pattern match for all messages that it can accept and if you
want to be able to handle unknown messages then you need to have a default case
as in the example above. Otherwise an ``akka.actor.UnhandledMessage(message,
sender, recipient)`` will be published to the ``ActorSystem``'s ``EventStream``.

Note further that the return type of the behavior defined above is ``Unit``; if
the actor shall reply to the received message then this must be done explicitly
as explained below.

The result of the :meth:`receive` method is a partial function object, which is
stored within the actor as its “initial behavior”.

Here is s slightly bigger example:

.. includecode:: ../../../akka-samples/akka-sample-java8/src/main/java/sample/java8/SampleActor.java

TODO:
-----

Lots of doc missing here
