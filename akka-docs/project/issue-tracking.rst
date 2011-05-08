.. _issue_tracking:

Issue Tracking
==============

Akka is using ``Assembla`` as issue tracking system.

Browsing
--------

Tickets
^^^^^^^

`You can find the Akka tickets here <http://www.assembla.com/spaces/akka/tickets>`_

`You can find the Akka Modules tickets here <https://www.assembla.com/spaces/akka-modules/tickets>`_

Roadmaps
^^^^^^^^

`The roadmap for each Akka milestone is here <https://www.assembla.com/spaces/akka/milestones>`_

`The roadmap for each Akka Modules milestone is here <https://www.assembla.com/spaces/akka-modules/milestones>`_

Creating tickets
----------------

In order to create tickets you need to do the following:

`Register here <https://www.assembla.com/user/signup>`_ then log in

For Akka tickets:
`Link to create new ticket <https://www.assembla.com/spaces/akka/tickets/new>`__


For Akka Modules tickets:
`Link to create new ticket <https://www.assembla.com/spaces/akka-modules/tickets/new>`__

Thanks a lot for reporting bugs and suggesting features.

Failing test
------------

Please submit a failing test on the following format:

.. code-block:: scala

  import org.scalatest.WordSpec
  import org.scalatest.matchers.MustMatchers

  class Ticket001Spec extends WordSpec with MustMatchers {
  
    "An XXX" should {
      "do YYY" in {
        1 must be (1)
      }
    }
  }
