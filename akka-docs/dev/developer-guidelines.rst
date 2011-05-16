.. _developer_guidelines:

Developer Guidelines
====================

Code Style
----------

The Akka code style follows `this document <http://davetron5000.github.com/scala-style/ScalaStyleGuide.pdf>`_ .

Here is a code style settings file for ``IntelliJ IDEA``:
`Download <../_static/akka-intellij-code-style.jar>`_

Please follow the code style. Look at the code around you and mimic.

Testing
-------

All code that is checked in **should** have tests. All testing is done with ``ScalaTest`` and ``ScalaCheck``.

* Name tests as **Test.scala** if they do not depend on any external stuff. That keeps surefire happy.
* Name tests as **Spec.scala** if they have external dependencies.

There is a testing standard that should be followed: `Ticket001Spec <https://github.com/jboner/akka/blob/master/akka-actor-tests/src/test/scala/akka/ticket/Ticket001Spec.scala>`_

Actor TestKit
^^^^^^^^^^^^^

There is a useful test kit for testing actors: `akka.util.TestKit <https://github.com/jboner/akka/tree/master/akka-testkit/src/main/scala/akka/testkit/TestKit.scala>`_. It enables assertions concerning replies received and their timing, there is more documentation in the :ref:`akka-testkit` module.

NetworkFailureTest
^^^^^^^^^^^^^^^^^^

You can use the 'NetworkFailureTest' trait to test network failure. See the 'RemoteErrorHandlingNetworkTest' test. Your tests needs to end with 'NetworkTest'. They are disabled by default. To run them you need to enable a flag.

Example:

::

  project akka-remote
  set akka.test.network true
  test-only akka.actor.remote.RemoteErrorHandlingNetworkTest

It uses 'ipfw' for network management. Mac OSX comes with it installed but if you are on another platform you might need to install it yourself. Here is a port:

`<http://info.iet.unipi.it/~luigi/dummynet>`_
