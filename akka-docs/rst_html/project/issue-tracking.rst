.. _issue_tracking:

Issue Tracking
==============

Akka is using ``Assembla`` as its issue tracking system.

Browsing
--------

Tickets
^^^^^^^

`You can find the Akka tickets here <http://www.assembla.com/spaces/akka/tickets>`_

Roadmaps
^^^^^^^^

`The roadmap for each Akka milestone is here <https://www.assembla.com/spaces/akka/milestones>`_


Creating tickets
----------------

In order to create tickets you need to do the following:

`Register here <https://www.assembla.com/user/signup>`_ then log in

Then you also need to become a "Watcher" of the Akka space.

`Link to create a new ticket <https://www.assembla.com/spaces/akka/tickets/new>`__

Thanks a lot for reporting bugs and suggesting features.


Failing test
------------

Please submit a failing test on the following format:

.. code-block:: scala

  import org.scalatest.WordSpec
  import org.scalatest.matchers.MustMatchers

  class Ticket001Spec extends WordSpec with MustMatchers {

    "An XXX" must {
      "do YYY" in {
        1 must be (1)
      }
    }
  }
