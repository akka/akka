.. _developer_guidelines:

Developer Guidelines
====================

Code Style
----------

The Akka code style follows `this document <http://davetron5000.github.com/scala-style/ScalaStyleGuide.pdf>`_ .

Akka is using ``Scalariform`` to format the source code as part of the build. So just hack away and then run ``sbt compile`` and it will reformat the code according to Akka standards.

Process
-------

* Pick a ticket, if there is no ticket for your work then create one first.
* Start working in a feature branch. Name it something like ``wip-<descriptive name or ticket number>-<your username>``.
* When you are done send a request for review to the Akka developer mailing list. If successfully review, merge into master.

Testing
-------

All code that is checked in **should** have tests. All testing is done with ``ScalaTest`` and ``ScalaCheck``.

* Name tests as **Test.scala** if they do not depend on any external stuff. That keeps surefire happy.
* Name tests as **Spec.scala** if they have external dependencies.

There is a testing standard that should be followed: `Ticket001Spec <https://github.com/jboner/akka/blob/master/akka-actor-tests/src/test/scala/akka/ticket/Ticket001Spec.scala>`_

Actor TestKit
^^^^^^^^^^^^^

There is a useful test kit for testing actors: `akka.util.TestKit <https://github.com/jboner/akka/tree/master/akka-testkit/src/main/scala/akka/testkit/TestKit.scala>`_. It enables assertions concerning replies received and their timing, there is more documentation in the :ref:`akka-testkit` module.

Multi-JVM Testing
^^^^^^^^^^^^^^^^^

Included in the example is an sbt trait for multi-JVM testing which will fork
JVMs for multi-node testing. There is support for running applications (objects
with main methods) and running ScalaTest tests.

NetworkFailureTest
^^^^^^^^^^^^^^^^^^

You can use the 'NetworkFailureTest' trait to test network failure.