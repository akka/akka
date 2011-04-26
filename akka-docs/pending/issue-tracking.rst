Issue Tracking
==============

Akka is using Assembla as issue tracking system.

Browsing
--------

You can find the Akka tickets here: `<http://www.assembla.com/spaces/akka>`_
The roadmap for each milestone is here: `<https://www.assembla.com/spaces/akka/milestones>`_

Creating tickets
----------------

In order to create tickets you need to do the following:

# Register here: `<https://www.assembla.com/user/signup>`_
# Log in
# Create the ticket: `<https://www.assembla.com/spaces/akka/tickets/new>`_

Thanks a lot for reporting bugs and suggesting features.

Failing test
------------

Please submit a failing test on the following format:

`<code format="scala">`_

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

class Ticket001Spec extends WordSpec with MustMatchers {

  "An XXX" should {
    "do YYY" in {
      1 must be (1)
    }
  }
}
`<code>`_
