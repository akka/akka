
Handling blocking operations
============================
Sometimes it is difficult to avoid performing the blocking operations and there
are good chances that the blocking is done inside a Future execute, which may
lead to problems. It is important to handle the blocking operations correctly.

This document explains the common problems that may arise due to incorrect
handling and how to solve such problems.


Problem
-------
Using ``context.dispatcher`` as the dispatcher on which the blocking Future
executes, can be a problem. The same dispatcher is used by the routing
infrastructure to actually handle the incoming requests. If all of the available
threads are blocked, the routing infrastructure will end up starving. Therefore,
routing infrastructure should not be blocked. Instead, a dedicated dispatcher
for blocking operations should be used.

Blocking APIs should also be avoided if possible. If it is not possible to avoid
blocking APIs, then following solution explains how to handle blocking
operations properly.


Solution
--------
Following is the investigation of three pieces of code that explains the
dispatcher impact and the performance of the app.

The thread color state is represented as follows:-

* Turquoise - Sleeping state
* Orange - Waiting state
* Green - Runnable state

**1. Dispatcher behaviour on bad code**::

  //Bad(due to blocking in Future):
  implicit val defaultDispatcher = system.dispatcher
  val routes: Route = post {
  complete {
    Future {                               // uses defaultDispatcher
      Thread.sleep(5000)                     // will block on default dispatcher,
      System.currentTimeMillis().toString    // Starving the routing infra
      }
    }
  }

Here the app is exposed to load of continous GET requests and large number
of akka.actor.default-dispatcher threads are handling requests. The orange
portion of the thread shows that they are idle.

  .. image:: DispatcherBehaviourOnBadCode.png

After some time, the app is exposed to the load of requesting POST requests,
which will block these threads. For example "default-akka.default-dispatcher2,3,4"
are going into the blocking state, after being idle before. It can be observed
that the number of new threads increase, "default-akka.actor.default-dispatcher 18,
19,20,..." however they go to sleep state immediately, thus wasting the
resources.

The number of such new threads depend on the default dispatcher configuration,
but likely will not exceed 50. Since many POST requests are done, the entire
thread pool is starved. The blocking operations dominate such that the routing
infra has no thread available to handle the other requests.


**2. Dispatcher behaviour good structured code/dispatcher**

In ``application.conf``, the dispatcher dedicated for blocking behaviour should
be configured as follows::

  my-blocking-dispatcher {
    type = Dispatcher
    executor = "thread-pool-executor"
    thread-pool-executer {
      // in Akka previous to 2.4.2:
      core-pool-size-min = 16
      core-pool-size-max = 16
      max-pool-size-min = 16
      max-pool-size-max = 16
      // or in Akka 2.4.2+
      fixed-pool-size = 16
    }
      throughput = 100
  }

There are many dispatcher options available which can be found here
`Akka Dispatchers <http://doc.akka.io/docs/akka/snapshot/scala/dispatchers.html>`_.

Here ``thread-pool-executer`` is used, which has a hard limit of threads, it can
keep available for blocking operations. The size settings depend on the app
functionality and the number of cores the server has.

Whenever blocking has to be done, use the above configured dispatcher
instead of the default one::

  // Good (due to the blocking in Future):
  implicit val blockingDispatcher = system.dispatcher.lookup("my-blocking-dispatcher")
  val routes: Route = post {
    complete {
      Future {  // uses the good "blocking dispatcher" that we configured,
                  // instead of the default dispatcher- the blocking is isolated.
       Thread.sleep(5000)
       System.currentTimeMillis().toString
      }
    }
  }

This forces the app to use the same load, initially normal requests and then
the blocking requests. The thread pool behaviour is shown in the figrue.

    .. image:: DispatcherBehaviourOnGoodCode.png

Initially, the normal requests are easily handled by default dispatcher, the
green lines, which represents the actual execution.

When blocking operations are issued, the ``my-blocking-dispatcher``
starts up to the number of configured threads. It handles sleeping. After
certain period of nothing happening to the threads, it shuts them down.

If another bunch of operations have to be done, the pool will start new
threads that will take care of putting them into sleep state, but the
threads are not wasted.

In this case, the throughput of the normal GET requests are not impacted
they were still served on the default dispatcher.

This is the recommended way of dealing with any kind of blocking in reactive
applications. It is referred as "bulkheading" or "isolating" the bad behaving
parts of an app. In this case, bad behaviour of blocking operations.

**3.Dispatcher behaviour when blocking applied properly**

Here scala.concurrent.blocking method is used to handle the blocking operations.
It causes more threads to be spun to survive the blocking operations::

  // default dispatcher with blocking
  implicit val dispatcher = system.dispatcher
  val routes: Route = post {
    complete {
      Future {        // uses the default dispatcher (like fork-join pool)
        blocking {    // will cause much more threads to spun-up, avoiding
                      // starvation somewhat, but at the cost of exploding the
                      // number of threads (which eventually may also lead to
                      // starvation problems, but on a different layer)
          Thread.sleep(5000)
          System.currentTimeMillis().toString
        }
      }
    }
  }

App will behave like:-

  .. image:: DispatcherBehaviourProperBlocking.png

It can be observed that lot of new threads are created, because of the blocking
hints - since blocking will be done, more threads should be created. This causes
the total blocked time to be smaller than first example code. However, there
are hundreds of threads doing nothing after the blocking operations have finished.
They will shut down, but there will be a large uncontrolled amount of threads
running. In contrast to second solution where we know how many threads we are
dedicating for the blocking behaviour.

There is good documentation availabe in Akka docs section, `Blocking needs careful management <http://doc.akka.io/docs/akka/current/general/actor-systems.html#Blocking_Needs_Careful_Management>`_.
