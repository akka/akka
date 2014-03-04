.. _remoting:

Location Transparency
=====================

The previous section describes how actor paths are used to enable location
transparency. This special feature deserves some extra explanation, because the
related term “transparent remoting” was used quite differently in the context
of programming languages, platforms and technologies.

Distributed by Default
----------------------

Everything in Akka is designed to work in a distributed setting: all
interactions of actors use purely message passing and everything is
asynchronous. This effort has been undertaken to ensure that all functions are
available equally when running within a single JVM or on a cluster of hundreds
of machines. The key for enabling this is to go from remote to local by way of
optimization instead of trying to go from local to remote by way of
generalization. See `this classic paper
<http://doc.akka.io/docs/misc/smli_tr-94-29.pdf>`_
for a detailed discussion on why the second approach is bound to fail.

Ways in which Transparency is Broken
------------------------------------

What is true of Akka need not be true of the application which uses it, since
designing for distributed execution poses some restrictions on what is
possible. The most obvious one is that all messages sent over the wire must be
serializable. While being a little less obvious this includes closures which
are used as actor factories (i.e. within :class:`Props`) if the actor is to be
created on a remote node.

Another consequence is that everything needs to be aware of all interactions
being fully asynchronous, which in a computer network might mean that it may
take several minutes for a message to reach its recipient (depending on
configuration). It also means that the probability for a message to be lost is
much higher than within one JVM, where it is close to zero (still: no hard
guarantee!).

How is Remoting Used?
---------------------

We took the idea of transparency to the limit in that there is nearly no API
for the remoting layer of Akka: it is purely driven by configuration. Just
write your application according to the principles outlined in the previous
sections, then specify remote deployment of actor sub-trees in the
configuration file. This way, your application can be scaled out without having
to touch the code. The only piece of the API which allows programmatic
influence on remote deployment is that :class:`Props` contain a field which may
be set to a specific :class:`Deploy` instance; this has the same effect as
putting an equivalent deployment into the configuration file (if both are
given, configuration file wins).

Peer-to-Peer vs. Client-Server
------------------------------

Akka Remoting is a communication module for connecting actor systems in a peer-to-peer fashion,
and it is the foundation for Akka Clustering. The design of remoting is driven by two (related)
design decisions:

#. Communication between involved systems is symmetric: if a system A can connect to a system B
   then system B must also be able to connect to system A independently.
#. The role of the communicating systems are symmetric in regards to connection patterns: there
   is no system that only accepts connections, and there is no system that only initiates connections.
   
The consequence of these decisions is that it is not possible to safely create
pure client-server setups with predefined roles (violates assumption 2) and
using setups involving Network Address Translation or Load Balancers (violates
assumption 1).

For client-server setups it is better to use HTTP or Akka I/O.

Marking Points for Scaling Up with Routers
------------------------------------------

In addition to being able to run different parts of an actor system on
different nodes of a cluster, it is also possible to scale up onto more cores
by multiplying actor sub-trees which support parallelization (think for example
a search engine processing different queries in parallel). The clones can then
be routed to in different fashions, e.g. round-robin. The only thing necessary
to achieve this is that the developer needs to declare a certain actor as
“withRouter”, then—in its stead—a router actor will be created which will spawn
up a configurable number of children of the desired type and route to them in
the configured fashion. Once such a router has been declared, its configuration
can be freely overridden from the configuration file, including mixing it with
the remote deployment of (some of) the children. Read more about
this in :ref:`Routing (Scala) <routing-scala>` and :ref:`Routing (Java) <routing-java>`.
