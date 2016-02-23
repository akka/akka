.. _developer_guidelines:

Developer Guidelines
====================

.. note::

    First read `The Akka Contributor Guidelines`_.

Code Style
----------

The Akka code style follows the `Scala Style Guide <http://docs.scala-lang.org/style/>`_ . The only exception is the
style of block comments:

.. code-block:: scala

  /**
    * Style mandated by "Scala Style Guide"
    */

  /**
   * Style adopted in the Akka codebase
   */

Akka is using ``Scalariform`` to format the source code as part of the build. So just hack away and then run ``sbt compile`` and it will reformat the code according to Akka standards.

Process
-------

The full process is described in `The Akka Contributor Guidelines`_. In summary:

* Make sure you have signed the Akka CLA, if not, `sign it online <http://www.lightbend.com/contribute/cla>`_.
* Pick a ticket, if there is no ticket for your work then create one first.
* Fork `akka/akka <https://github.com/akka/akka>`_. Start working in a feature branch.
* When you are done, create a GitHub Pull-Request towards the targeted branch.
* When there's consensus on the review, someone from the Akka Core Team will merge it.

Commit messages
---------------

Please follow the conventions described in `The Akka Contributor Guidelines`_ when creating public commits and writing commit messages.

Testing
-------

All code that is checked in **should** have tests. All testing is done with ``ScalaTest`` and ``ScalaCheck``.

* Name tests as **Test.scala** if they do not depend on any external stuff. That keeps surefire happy.
* Name tests as **Spec.scala** if they have external dependencies.

Actor TestKit
^^^^^^^^^^^^^

There is a useful test kit for testing actors: `akka.util.TestKit <@github@/akka-testkit/src/main/scala/akka/testkit/TestKit.scala>`_. It enables assertions concerning replies received and their timing, there is more documentation in the :ref:`akka-testkit` module.

Multi-JVM Testing
^^^^^^^^^^^^^^^^^

Included in the example is an sbt trait for multi-JVM testing which will fork
JVMs for multi-node testing. There is support for running applications (objects
with main methods) and running ScalaTest tests.

NetworkFailureTest
^^^^^^^^^^^^^^^^^^

You can use the 'NetworkFailureTest' trait to test network failure.

.. _The Akka Contributor Guidelines: https://github.com/akka/akka/blob/master/CONTRIBUTING.md
