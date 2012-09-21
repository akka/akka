Why Akka?
=========

What features can the Akka platform offer, over the competition?
----------------------------------------------------------------

Akka provides scalable real-time transaction processing.

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


What's a good use-case for Akka?
--------------------------------

We see Akka being adopted by many large organizations in a big range of industries
all from investment and merchant banking, retail and social media, simulation,
gaming and betting, automobile and traffic systems, health care, data analytics
and much more. Any system that have the need for high-throughput and low latency
is a good candidate for using Akka.

Actors lets you manage service failures (Supervisors), load management (back-off
strategies, timeouts and processing-isolation), both horizontal and vertical
scalability (add more cores and/or add more machines).

Here's what some of the Akka users have to say about how they are using Akka:
http://stackoverflow.com/questions/4493001/good-use-case-for-akka

All this in the ApacheV2-licensed open source project.
