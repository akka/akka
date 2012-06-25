.. _akka-testkit-java:

##############################
Testing Actor Systems (Java)
##############################

Due to the conciseness of test DSLs available for Scala, it may be a good idea
to write the test suite in that language even if the main project is written in
Java. If that is not desirable, you can also use :class:`TestKit` and friends
from Java, albeit with more verbose syntax Munish Gupta has `published a nice
post <http://www.akkaessentials.in/2012/05/using-testkit-with-java.html>`_
showing several patterns you may find useful, and for reference documentation
please refer to :ref:`akka-testkit` until that section has been ported over to
cover Java in full.
