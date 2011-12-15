Why Akka?
=========

What features can the Akka platform offer, over the competition?
----------------------------------------------------------------

Akka is an unified runtime and programming model for:

- Scale up (Concurrency)
- Scale out (Remoting)
- Fault tolerance

One thing to learn and admin, with high cohesion and coherent semantics.

Akka is a very scalable piece of software, not only in the performance sense,
but in the size of applications it is useful for. The core of Akka, akka-actor,
is very small and easily dropped into an existing project where you need
asynchronicity and lockless concurrency without hassle.

You can choose to include only the parts of akka you need in your application
and then there's the whole package, the Akka Microkernel, which is a standalone
container to deploy your Akka application in. With CPUs growing more and more
cores every cycle, Akka is the alternative that provides outstanding performance
even if you're only running it on one machine. Akka also supplies a wide array
of concurrency-paradigms, allowing for users to choose the right tool for the
job.

The integration possibilities for Akka Actors are immense through the Apache
Camel integration. We have Transactors for coordinated concurrent transactions,
as well as Agents and Dataflow concurrency.


What's a good use-case for Akka?
--------------------------------

(Web, Cloud, Application) Services - Actors lets you manage service failures
(Supervisors), load management (back-off strategies, timeouts and
processing-isolation), both horizontal and vertical scalability (add more cores
and/or add more machines). Think payment processing, invoicing, order matching,
datacrunching, messaging. Really any highly transactional systems like banking,
betting, games.

Here's what some of the Akka users have to say about how they are using Akka:
http://stackoverflow.com/questions/4493001/good-use-case-for-akka


Akka Atmos
----------

And that's all in the ApacheV2-licensed open source project. On top of that we
have a commercial product called Akka Atmos which provides the following
features:

#. Management through Dashboard, JMX and REST
#. Dapper-style tracing of messages across components and remote nodes
#. A configurable alert system
#. Real-time statistics
#. Very low overhead monitoring agents (should always be on in production)
#. Consolidation of statistics and logging information to a single node
#. Storage of statistics data for later processing
#. Provisioning and rolling upgrades

Read more `here <http://typesafe.com/products/typesafe-subscription>`_.
