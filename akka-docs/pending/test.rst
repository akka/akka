Testing of Akka
===============

Introduction
============

Testing concurrent code using time-outs (like Thread.sleep(..)) is usually a bad idea since it is both slow and error-prone. There are some frameworks that can help, some are listed below.

Testing Actor Interaction
=========================

For Actor interaction, making sure certain message arrives in time etc. we recommend you use Akka's built-in `TestKit <testkit>`_. If you want to roll your own, you will find helpful abstractions in the `java.util.concurrent` package, most notably `BlockingQueue` and `CountDownLatch`.

Unit testing of Actors
======================

If you need to unit test your actors then the best way to do that would be to decouple it from the Actor by putting it in a regular class/trait, test that, and then mix in the Actor trait when you want to create actors. This is necessary since you can't instantiate an Actor class directly with 'new'. But note that you can't test Actor interaction with this, but only local Actor implementation. Here is an example:

.. code-block:: scala

  // test this
  class MyLogic {
    def blabla: Unit = {
      ...
    }
  }

  // run this
  actorOf(new MyLogic with Actor {
   def receive = {
      case Bla => blabla
   }
  })

...or define a non-anonymous MyLogicActor class.

Akka Expect
===========

Expect mimic for testing Akka actors.

`<https://github.com/joda/akka-expect>`_

Awaitility
==========

Not a Akka specific testing framework but a nice DSL for testing asynchronous code.
Scala and Java API.

`<http://code.google.com/p/awaitility/>`_

ScalaTest Conductor
===================

`<http://www.scalatest.org/scaladoc/doc-1.0/org/scalatest/concurrent/Conductor.html>`_
