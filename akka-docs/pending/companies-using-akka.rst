Companies and Open Source projects using Akka
=============================================

Production Users
****************

These are some of the production Akka users that are able to talk about their use publicly.

CSC
---

CSC is a global provider of information technology services. The Traffic Management business unit in the Netherlands is a systems integrator for the implementation of Traffic Information and Traffic Enforcement Systems, such as section control, weigh in motion, travel time and traffic jam detection and national data warehouse for traffic information. CSC Traffic Management is using Akka for their latest Traffic Information and Traffic Enforcement Systems.

`<http://www.csc.com/nl/ds/42449-traffic_management>`_

*"Akka has been in use for almost a year now (since 0.7) and has been used successfully for two projects so far. Akka has enabled us to deliver very flexible, scalable and high performing systems with as little friction as possible. The Actor model has simplified a lot of concerns in the type of systems that we build and is now part of our reference architecture. With Akka we deliver systems that meet the most strict performance requirements of our clients in a near-realtime environment. We have found the Akka framework and it's support team invaluable."*

Thatcham Motor Insurance Repair Research Centre
-----------------------------------------------

Thatcham is a EuroNCAP member. They research efficient, safe, cost effective repair of vehicles, and work with manufacturers to influence the design of new vehicles Thatcham are using Akka as the implementation for their distributed modules. All Scala based research software now talks to an Akka based publishing platform. Using Akka enables Thatcham to 'free their domain', and ensures that the platform is cloud enabled and scalable, and that the team is confident that they are flexible. Akka has been in use, tested under load at Thatcham for almost a year, with no problems migrating up through the different versions. An old website currently under redesign on a new Scala powered platform: `www.thatcham.org <http://www.thatcham.org>`_

*“We have been in production with Akka for over 18 months with zero downtime. The core is rock solid, never a problem, performance is great, integration capabilities are diverse and ever growing, and the toolkit is just a pleasure to work with. Combine that with the excellent response you get from the devs and users on this list and you have a winner. Absolutely no regrets on our part for choosing to work with Akka.”*

*"Scala and Akka are now enabling improvements in the standard of vehicle damage assessment, and in the safety of vehicle repair across the UK, with Europe, USA, Asia and Australasia to follow. Thatcham (Motor Insurance Repair Research Centre) are delivering crash specific information with linked detailed repair information for over 7000 methods.*

*For Thatcham, the technologies enable scalability and elegance when dealing with complicated design constraints. Because of the complexity of interlinked methods, caching is virtually impossible in most cases, so in steps the 'actors' paradigm. Where previously something like JMS would have provided a stable but heavyweight, rigid solution, Thatcham are now more flexible, and can expand into the cloud in a far simpler, more rewarding way.*

*Thatcham's customers, body shop repairers and insurers receive up to date repair information in the form of crash repair documents of the quality necessary to ensure that every vehicle is repaired back to the original safety standard. In a market as important as this, availability is key, as is performance. Scala and Akka have delivered consistently so far.*

*While recently introduced, growing numbers of UK repairers are receiving up to date repair information from this service, with the rest to follow shortly. Plans are already in motion to build new clusters to roll the service out across Europe, USA, Asia and Australasia.*

*The sheer opportunities opened up to teams by Scala and Akka, in terms of integration, concise expression of intent and scalability are of huge benefit."*

SVT (Swedish Television)
------------------------

`<http://svt.se>`_

*“I’m currently working in a project at the Swedish Television where we’re developing a subtitling system with collaboration capabilities similar to Google Wave. It’s a mission critical system and the design and server implementation is all based on Akka and actors etc. We’ve been running in production for about 6 months and have been upgrading Akka whenever a new release comes out. We’ve never had a single bug due to Akka, and it’s been a pure pleasure to work with. I would choose Akka any day of the week!*

*Our system is highly asynchronous so the actor style of doing things is a perfect fit. I don’t know about how you feel about concurrency in a big system, but rolling your own abstractions is not a very easy thing to do. When using Akka you can almost forget about all that. Synchronizing between threads, locking and protecting access to state etc. Akka is not just about actors, but that’s one of the most pleasurable things to work with. It’s easy to add new ones and it’s easy to design with actors. You can fire up work actors tied to a specific dispatcher etc. I could make the list of benefits much longer, but I’m at work right now. I suggest you try it out and see how it fits your requirements.*

*We saw a perfect business reason for using Akka. It lets you concentrate on the business logic instead of the low level things. It’s easy to teach others and the business intent is clear just by reading the code. We didn’t chose Akka just for fun. It’s a business critical application that’s used in broadcasting. Even live broadcasting. We wouldn’t have been where we are today in such a short time without using Akka. We’re two developers that have done great things in such a short amount of time and part of this is due to Akka. As I said, it lets us focus on the business logic instead of low level things such as concurrency, locking, performance etc."*

Tapad
-----

`<http://tapad.com>`_

*"Tapad is building a real-time ad exchange platform for advertising on mobile and connected devices. Real-time ad exchanges allows for advertisers (among other things) to target audiences instead of buying fixed set of ad slots that will be displayed “randomly” to users. To developers without experience in the ad space, this might seem boring, but real-time ad exchanges present some really interesting technical challenges.*

*Take for instance the process backing a page view with ads served by a real-time ad exchange auction (somewhat simplified):*

1. *A user opens a site (or app) which has ads in it.*
2. *As the page / app loads, the ad serving components fires off a request to the ad exchange (this might just be due to an image tag on the page).*
3. *The ad exchange enriches the request with any information about the current user (tracking cookies are often employed for this) and and display context information (“news article about parenting”, “blog about food” etc).*
4. *The ad exchange forwards the enriched request to all bidders registered with the ad exchange.*
5. *The bidders consider the provided user information and responds with what price they are willing to pay for this particular ad slot.*
6. *The ad exchange picks the highest bidder and ensures that the winning bidder’s ad is shown to to user.*

*Any latency in this process directly influences user experience latency, so this has to happen really fast. All-in-all, the total time should not exceed about 100ms and most ad exchanges allow bidders to spend about 60ms (including network time) to return their bids. That leaves the ad exchange with less than 40ms to facilitate the auction. At Tapad, this happens billions of times per month / tens of thousands of times per second.*

*Tapad is building bidders which will participate in auctions facilitated by other ad exchanges, but we’re also building our own. We are using Akka in several ways in several parts of the system. Here are some examples:*

*Plain old parallelization*
*During an auction in the real-time exchange, it’s obvious that all bidders must receive the bid requests in parallel. An auctioneer actor sends the bid requests to bidder actors which in turn handles throttling and eventually IO. We use futures in these requests and the auctioneer discards any responses which arrive too late.*

*Inside our bidders, we also rely heavily on parallel execution. In order to determine how much to pay for an ad slot, several data stores are queried for information pertinent to the current user. In a “traditional” system, we’d be doing this sequentially, but again, due to the extreme latency constraints, we’re doing this concurrently. Again, this is done with futures and data that is not available in time, get cut from the decision making (and logged :)).*

*Maintaining state under concurrent load*
*This is probably the de facto standard use case for the actors model. Bidders internal to our system are actors backed by a advertiser campaign. A campaign includes, among other things, budget and “pacing” information. The budget determines how much money to spend for the duration of the campaign, whereas pacing information might set constraints on how quickly or slowly the money should be spent. Ad traffic changes from day to day and from hour to hour and our spending algorithms considers past performance in order to spend the right amount of money at the right time. Needless to say, these algorithms use a lot of state and this state is in constant flux. A bidder with a high budget may see tens of thousands of bid requests per second. Luckily, due to round-robin load-balancing and the predictability of randomness under heavy traffic, the bidder actors do not share state across cluster nodes, they just share their instance count so they know which fraction of the campaign budget to try to spend.*

*Pacing is also done for external bidders. Each 3rd party bidder end-point has an actor coordinating requests and measuring latency and throughput. The actor never blocks itself, but when an incoming bid request is received, it considers the current performance of the 3rd party system and decides whether to pass on the request and respond negatively immediately, or forward the request to the 3rd party request executor component (which handles the IO).*

*Batch processing*
*We store a lot of data about every single ad request we serve and this is stored in a key-value data store. Due to the performance characteristics of the data store, it is not feasible to store every single data point one at at time - it must be batched up and performed in parallel. We don’t need a durable messaging system for this (losing a couple of hundred data points is no biggie). All our data logging happens asynchronously and we have a basic load-balanced actors which batches incoming messages and writes on regular intervals (using Scheduler) or whenever the specified batch size has been reached.*

*Analytics*
*Needless to say, it’s not feasible / useful to store our traffic information in a relational database. A lot of analytics and data analysis is done “offline” with map / reduce on top the data store, but this doesn’t work well for real-time analytics which our customers love. We therefore have metrics actors that receives campaign bidding and click / impression information in real-time, aggregates this information over configurable periods of time and flushes it to the database used for customer dashboards for “semi-real-time” display. Five minute history is considered real-time in this business, but in theory, we could have queried the actors directly for really real-time data. :)*

*Our Akka journey started as a prototyping project, but Akka has now become a crucial part of our system. All of the above mentioned components, except the 3rd party bidder integration, have been running in production for a couple of weeks (on Akka 1.0RC3) and we have not seen any issues at all so far."*

Flowdock
--------

Flowdock delivers Google Wave for the corporate world.

*"Flowdock makes working together a breeze. Organize the flow of information, task things over and work together towards common goals seamlessly on the web - in real time."*

`<http://flowdock.com/>`_

Travel Budget
-------------

`<http://labs.inevo.pt/travel-budget>`_

Says.US
-------

*"says.us is a gathering place for people to connect in real time - whether an informal meeting of people who love Scala or a chance for people anywhere to speak out about the latest headlines."*

`<http://says.us/>`_

LShift
------

* *"Diffa is an open source data analysis tool that automatically establishes data differences between two or more real-time systems.*
* Diffa will help you compare local or distributed systems for data consistency, without having to stop them running or implement manual cross-system comparisons. The interface provides you with simple visual summary of any consistency breaks and tools to investigate the issues.*
* Diffa is the ideal tool to use to investigate where or when inconsistencies are occurring, or simply to provide confidence that your systems are running in perfect sync. It can be used operationally as an early warning system, in deployment for release verification, or in development with other enterprise diagnosis tools to help troubleshoot faults."*

`<http://diffa.lshift.net/>`_

Twimpact
--------

*"Real-time twitter trends and user impact"*

`<http://twimpact.com>`_

Rocket Pack Platform
--------------------

*"Rocket Pack Platform is the only fully integrated solution for plugin-free browser game development."*

`<http://rocketpack.fi/platform/>`_

Open Source Projects using Akka
*******************************

Redis client
------------

*A Redis client written Scala, using Akka actors, HawtDispath and non-blocking IO. Supports Redis 2.0+*

`<http://github.com/derekjw/fyrie-redis>`_

Narrator
--------

*"Narrator is a a library which can be used to create story driven clustered load-testing packages through a very readable and understandable api."*

`<http://github.com/shorrockin/narrator>`_

Kandash
-------

*"Kandash is a lightweight kanban web-based board and set of analytics tools."*

`<http://vasilrem.com/blog/software-development/kandash-project-v-0-3-is-now-available/>`_
`<http://code.google.com/p/kandash/>`_

Wicket Cassandra Datastore
--------------------------

This project provides an org.apache.wicket.pageStore.IDataStore implementation that writes pages to an Apache Cassandra cluster using Akka.

`<http://github.com/gseitz/wicket-cassandra-datastore/>`_

Spray
-----

*"spray is a lightweight Scala framework for building RESTful web services on top of Akka actors and Akka Mist. It sports the following main features:*

* *Completely asynchronous, non-blocking, actor-based request processing for efficiently handling very high numbers of concurrent connections*
* *Powerful, flexible and extensible internal Scala DSL for declaratively defining your web service behavior*
* *Immutable model of the HTTP protocol, decoupled from the underlying servlet container*
* *Full testability of your REST services, without the need to fire up containers or actors"*

`<https://github.com/spray/spray/wiki>`_
