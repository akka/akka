.. _http-module:

HTTP
====

.. sidebar:: Contents

   .. contents:: :local:

Play2-mini
----------

The Akka team recommends the `Play2-mini <https://github.com/typesafehub/play2-mini>`_ framework when building RESTful
service applications that integrates with Akka. It provides a REST API on top of `Play2 <https://github.com/playframework/Play20/>`_.

Getting started
---------------

First you must make your application aware of play-mini.
In SBT you just have to add the following to your _libraryDependencies_::

  libraryDependencies += "com.typesafe" %% "play-mini" % "2.0-RC3-SNAPSHOT"

Note: At the time of this writing play-mini version 2.0-RC3-SNAPSHOT (the current snapshot) only works with
Akka versions 2.0-RC1 and below in the 2.x series.

Sample Application
------------------

To illustrate how easy it is to wire a RESTful service with Akka we will use a sample application.
The aim of the application is to show how to use play-mini and Akka in combination. Do not put too much
attention on the actual business logic itself, which is a extremely simple bank application, as building a bank
application is a little more complex than what's shown in the sample...

The application should support the following URL commands:
  - GET /ping - returns a Pong message with the time of the server (used to see if the application is up and running)
  - GET /account/statement/{accountId} - returns the account statement
  - POST /account/deposit - deposits money to an account (and creates a new one if it's not already existing)
  - POST /account/withdraw - withdraws money from an account

Error messages will be returned in case of any misuse of the application, e.g. withdrawing more money than an
account has etc.

Getting started
---------------

To build a play-mini application you first have to make your object extend com.typesafe.play.mini.Application:

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: playMiniDefinition

The next step is to implement the mandatory method ``route``:

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: route
   :exclude: routeLogic

It is inside the ``route`` method that all the magic happens.
In the sections below we will show how to set up play-mini to handle both GET and POST HTTP calls.

Simple GET
----------

We start off by creating the simplest method we can - a "ping" method:

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: simpleGET

As you can see in the section above play-mini uses Scala's wonderful pattern matching.
In the snippet we instruct play-mini to reply to all HTTP GET calls with the URI "/ping".
The ``Action`` returned comes from Play! and you can find more information about it `here <https://github.com/playframework/Play20/wiki/ScalaActions>`_.

.. _Advanced-GET:

Advanced GET
------------

Let's try something more advanced, retrieving parameters from the URI and also make an asynchronous call to an actor:

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: regexGET

The regular expression looks like this:

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: regexURI

In the snippets above we extract a URI parameter with the help of a simple regular expression and then we pass this
parameter on to the underlying actor system. As you can see ``AsyncResult`` is being used. This means that the call to
the actor will be performed asynchronously, i.e. no blocking.

The asynchronous call to the actor is being done with a ``ask``, e.g.::

    (accountActor ask Status(accountId))

The actor that receives the message returns the result by using a standard *sender !*
as can be seen here:

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: senderBang

When the result is returned to the calling code we use some mapping code in Play to convert a Akka future to a Play future.
This is shown in this code:

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: innerRegexGET

In this snippet we check the result to decide what type of response we want to send to the calling client.

Using HTTP POST
---------------

Okay, in the sections above we have shown you how to use play-mini for HTTP GET calls. Let's move on to when the user
posts values to the application.

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: asyncDepositPOST

As you can see the structure is almost the same as for the :ref:`Advanced-GET`. The difference is that we make the
``request`` parameter ``implicit`` and also that the following line of code is used to extract parameters from the POST.

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: formAsyncDepositPOST

The code snippet used to map the call to parameters looks like this:

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala
   :include: form

Apart from the mapping of parameters the call to the actor looks is done the same as in :ref:`Advanced-GET`.

The Complete Code Sample
------------------------

Below is the complete application in all its beauty.

Global.scala (<yourApp>/src/main/scala/Global.scala):

.. includecode:: code/Global.scala

PlayMiniApplication.scala (<yourApp>/src/main/scala/akka/docs/http/PlayMiniApplication.scala):

.. includecode:: code/akka/docs/http/PlayMiniApplication.scala

Build.scala (<yourApp>/project/Build.scala):

.. code-block:: scala

    import sbt._
    import Keys._

    object PlayMiniApplicationBuild extends Build {
      lazy val root = Project(id = "play-mini-application", base = file("."), settings = Project.defaultSettings).settings(
        libraryDependencies += "com.typesafe" %% "play-mini" % "2.0-RC3-SNAPSHOT",
        mainClass in (Compile, run) := Some("play.core.server.NettyServer"))
    }

Running the Application
-----------------------

Firstly, start up the application by opening a command terminal and type::

  > sbt
  > run

Now you should see something similar to this in your terminal window::

  [info] Running play.core.server.NettyServer
  Play server process ID is 2523
  [info] play - Application started (Prod)
  [info] play - Listening for HTTP on port 9000...

In this example we will use the awesome `cURL <http://en.wikipedia.org/wiki/CURL>`_ command to interact with the application.
Fire up a command terminal and try the application out::

  First we check the status of a couple of accounts:
  > curl http://localhost:9000/account/statement/TheDudesAccount
  Unknown account: TheDudesAccount
  > curl http://localhost:9000/account/statement/MrLebowskisAccount
  Unknown account: MrLebowskisAccount

  Now deposit some money to the accounts:
  > curl -d "accountId=TheDudesAccount&amount=1000" http://localhost:9000/account/deposit﻿﻿
  Updated account total: 1000
  > curl -d "accountId=MrLebowskisAccount&amount=500" http://localhost:9000/account/deposit
  Updated account total: 500

  Next thing is to check the status of the account:
  > curl http://localhost:9000/account/statement/TheDudesAccount
  Account total: 1000
  > curl http://localhost:9000/account/statement/MrLebowskisAccount
  Account total: 500

  Fair enough, let's try to withdraw some cash shall we:
  > curl -d "accountId=TheDudesAccount&amount=999" http://localhost:9000/account/withdraw
  Updated account total: 1
  > curl -d "accountId=MrLebowskisAccount&amount=999" http://localhost:9000/account/withdraw
  Unknown account or insufficient funds. Get your act together.
  > curl -d "accountId=MrLebowskisAccount&amount=500" http://localhost:9000/account/withdraw
  Updated account total: 0

Yeah, it works!
Now we leave it to the astute reader of this document to take advantage of the power of play-mini and Akka.