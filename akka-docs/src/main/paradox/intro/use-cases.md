
.. _use-cases:

################################
 Examples of use-cases for Akka
################################

We see Akka being adopted by many large organizations in a big range of industries
all from investment and merchant banking, retail and social media, simulation,
gaming and betting, automobile and traffic systems, health care, data analytics
and much more. Any system that have the need for high-throughput and low latency
is a good candidate for using Akka.

There is a great discussion on use-cases for Akka with some good write-ups by production
users `here <http://stackoverflow.com/questions/4493001/good-use-case-for-akka/4494512#4494512>`_

Here are some of the areas where Akka is being deployed into production
=======================================================================

Transaction processing (Online Gaming, Finance/Banking, Trading, Statistics, Betting, Social Media, Telecom)
------------------------------------------------------------------------------------------------------------
  Scale up, scale out, fault-tolerance / HA

Service backend (any industry, any app)
---------------------------------------
   Service REST, SOAP, Cometd, WebSockets etc
   Act as message hub / integration layer
   Scale up, scale out, fault-tolerance / HA

Concurrency/parallelism (any app)
---------------------------------
   Correct
   Simple to work with and understand
   Just add the jars to your existing JVM project (use Scala, Java, Groovy or JRuby)

Simulation
----------
   Master/Worker, Compute Grid, MapReduce etc.

Batch processing (any industry)
-------------------------------
   Camel integration to hook up with batch data sources
   Actors divide and conquer the batch workloads

Communications Hub (Telecom, Web media, Mobile media)
-----------------------------------------------------
   Scale up, scale out, fault-tolerance / HA

Gaming and Betting (MOM, online gaming, betting)
------------------------------------------------
   Scale up, scale out, fault-tolerance / HA

Business Intelligence/Data Mining/general purpose crunching
-----------------------------------------------------------
   Scale up, scale out, fault-tolerance / HA

Complex Event Stream Processing
-------------------------------
   Scale up, scale out, fault-tolerance / HA
