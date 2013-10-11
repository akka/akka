REMOTE CALCULATOR
=================

Requirements
------------

To build and run remote calculator you need [Simple Build Tool][sbt] (sbt).

The Sample Explained
--------------------

In order to showcase the remote capabilities of Akka 2.0 we thought a remote calculator could do the trick.

There are two implementations of the sample; one in Scala and one in Java.
The explanation below is for Scala, but everything is similar in Java except that the class names begin with a ``J``,
e.g. ``JCalcApp`` instead of ``CalcApp``, and that the Java classes reside in another package structure.

There are three actor systems used in the sample:

* CalculatorApplication : the actor system performing the number crunching (ie: the server)
* LookupApplication     : illustrates how to look up an actor on a remote node and communicate with that actor (ie: as a client)
* CreationApplication   : illustrates how to create an actor on a remote node and how to communicate with that actor (ie: as a client)

The CalculatorApplication contains an actor, SimpleCalculatorActor, which can handle simple math operations such as
addition and subtraction. The JVM this actor is run on is connected to and then this actor is looked up and used from the LookupApplication.

The CreationApplication wants to use more "advanced" mathematical operations, such as multiplication and division,
but as the CalculatorApplication does not have any actor that can perform those type of calculations the
CreationApplication has to remote deploy an actor that can (which in our case is AdvancedCalculatorActor).
So this actor is deployed, over the network, onto the CalculatorApplication actor system and thereafter the
CreationApplication will send messages to it.

It is important to point out that as the actor systems run on different ports it is possible to run all three in parallel.
See the next section for more information of how to run the sample application.

Running
-------

In order to run all three actor systems you have to start SBT in three different terminal windows.

We start off by running the CalculatorApplication:

First type 'sbt' to start SBT interactively, the run 'update' and 'run':
> cd $AKKA_HOME

> sbt

> project akka-sample-remote

> run

Select to run "sample.remote.calculator.CalcApp" which in the case below is number 3:

    Multiple main classes detected, select one to run:

    [1] sample.remote.calculator.LookupApp
    [2] sample.remote.calculator.CreationApp
    [3] sample.remote.calculator.java.JCreationApp
    [4] sample.remote.calculator.CalcApp
    [5] sample.remote.calculator.java.JCalcApp
    [6] sample.remote.calculator.java.JLookupApp

    Enter number: 4

You should see something similar to this::

    [info] Running sample.remote.calculator.CalcApp
    [INFO] [01/25/2013 15:02:51.355] [run-main] [Remoting] Starting remoting
    [INFO] [01/25/2013 15:02:52.121] [run-main] [Remoting] Remoting started; listening on addresses :[akka.tcp://CalculatorApplication@127.0.0.1:2552]
    Started Calculator Application - waiting for messages

Open up a new terminal window and run SBT once more:

> sbt

> project akka-sample-remote

> run

Select to run "sample.remote.calculator.LookupApp" which in the case below is number 1::

    Multiple main classes detected, select one to run:

    [1] sample.remote.calculator.LookupApp
    [2] sample.remote.calculator.CreationApp
    [3] sample.remote.calculator.java.JCreationApp
    [4] sample.remote.calculator.CalcApp
    [5] sample.remote.calculator.java.JCalcApp
    [6] sample.remote.calculator.java.JLookupApp

    Enter number: 1

Now you should see something like this::

    [info] Running sample.remote.calculator.LookupApp
    [INFO] [01/25/2013 15:05:53.954] [run-main] [Remoting] Starting remoting
    [INFO] [01/25/2013 15:05:54.769] [run-main] [Remoting] Remoting started; listening on addresses :[akka.tcp://LookupApplication@127.0.0.1:2553]
    Started Lookup Application
    Not ready yet
    Not ready yet
    Add result: 0 + 22 = 22
    Add result: 41 + 71 = 112
    Add result: 61 + 14 = 75
    Add result: 77 + 82 = 159

Congrats! You have now successfully looked up a remote actor and communicated with it.
The next step is to have an actor deployed on a remote note.
Once more you should open a new terminal window and run SBT:

> sbt

> project akka-sample-remote

> run

Select to run "sample.remote.calculator.CreationApp" which in the case below is number 2::

    Multiple main classes detected, select one to run:

    [1] sample.remote.calculator.LookupApp
    [2] sample.remote.calculator.CreationApp
    [3] sample.remote.calculator.java.JCreationApp
    [4] sample.remote.calculator.CalcApp
    [5] sample.remote.calculator.java.JCalcApp
    [6] sample.remote.calculator.java.JLookupApp

    Enter number: 2

Now you should see something like this::

    [info] Running sample.remote.calculator.CreationApp
    [INFO] [01/14/2013 15:08:08.890] [run-main] [Remoting] Starting remoting
    Started Creation Application
    Mul result: 15 * 12 = 180
    Div result: 3840 / 10 = 384,00
    Mul result: 1 * 5 = 5
    Div result: 3240 / 45 = 72,00

That's it!


Secure Cookie Handshake
-----------------------

An improvement that can be made is to have the CalculatorApplication verify that a known trusted actor is connecting to
it. This can be done using the 'Secure Cookie Handshake' mechanism. An example of enabling this is in the common.conf
file and looks as follows:

  # Uncomment the following four lines to employ the 'secure cookie handshake'
  # This requires the client to have the known secure-cookie and properly
  # transmit it to the server upon connection. Because both the client and server
  # programs use this common.conf file, they will both have the cookie
  #remote {
  #  secure-cookie = "0009090D040C030E03070D0509020F050B080400"
  #  require-cookie = on
  #}

In order to enable Secure Cookie Handshake, simply remove the #s from the 4 relevant lines as follows:

  # Uncomment the following four lines to employ the 'secure cookie handshake'
  # This requires the client to have the known secure-cookie and properly
  # transmit it to the server upon connection. Because both the client and server
  # programs use this common.conf file, they will both have the cookie
  remote {
    secure-cookie = "0009090D040C030E03070D0509020F050B080400"
    require-cookie = on
  }

Your CalculatorApplication actor will now verify the 'authenticity' of your LookupApp actor and the CreationApp actor.

In order to test that an invalid secure-cookie is rejected, you can simply do the following:

Select to run "sample.remote.calculator.CalcApp" which in the case below is number 3:

    Multiple main classes detected, select one to run:

    [1] sample.remote.calculator.LookupApp
    [2] sample.remote.calculator.CreationApp
    [3] sample.remote.calculator.java.JCreationApp
    [4] sample.remote.calculator.CalcApp
    [5] sample.remote.calculator.java.JCalcApp
    [6] sample.remote.calculator.java.JLookupApp

    Enter number: 4

You should see something similar to this::

    [info] Running sample.remote.calculator.CalcApp
    [INFO] [01/25/2013 15:02:51.355] [run-main] [Remoting] Starting remoting
    [INFO] [01/25/2013 15:02:52.121] [run-main] [Remoting] Remoting started; listening on addresses :[akka.tcp://CalculatorApplication@127.0.0.1:2552]
    Started Calculator Application - waiting for messages



Now edit the common.conf file to alter the value of secure-cookie.


Now, in a separate terminal, run the following:

> sbt

> project akka-sample-remote

> run

Select to run "sample.remote.calculator.LookupApp" which in the case below is number 1::

    Multiple main classes detected, select one to run:

    [1] sample.remote.calculator.LookupApp
    [2] sample.remote.calculator.CreationApp
    [3] sample.remote.calculator.java.JCreationApp
    [4] sample.remote.calculator.CalcApp
    [5] sample.remote.calculator.java.JCalcApp
    [6] sample.remote.calculator.java.JLookupApp

    Enter number: 1

Now you should see something like this::


    [info] Running sample.remote.calculator.LookupApp
    [INFO] [07/26/2013 16:27:31.265] [run-main] [Remoting] Starting remoting
    [INFO] [07/26/2013 16:27:31.489] [run-main] [Remoting] Remoting started; listening on addresses :[akka.tcp://LookupApplication@127.0.0.1:2553]
    [INFO] [07/26/2013 16:27:31.492] [run-main] [Remoting] Remoting now listens on addresses: [akka.tcp://LookupApplication@127.0.0.1:2553]
    Started Lookup Application
    Not ready yet
    [ERROR] [07/26/2013 16:27:31.691] [LookupApplication-akka.actor.default-dispatcher-2] [akka://LookupApplication/system/endpointManager/reliableEndpointWriter-akka.tcp%3A%2F%2FCalculatorApplication%40127.0.0.1%3A2552-0/endpointWriter] AssociationError [akka.tcp://LookupApplication@127.0.0.1:2553] -> [akka.tcp://CalculatorApplication@127.0.0.1:2552]: Error [Association failed with [akka.tcp://CalculatorApplication@127.0.0.1:2552]] [
    akka.remote.EndpointAssociationException: Association failed with [akka.tcp://CalculatorApplication@127.0.0.1:2552]
    Caused by: akka.remote.transport.AkkaProtocolException: The remote system explicitly disassociated (reason unknown).
    ]

You can see that the client LookupApp was unable to connect to the CalculatorApplication's AKKA system.


Notice
------

The sample application is just that, i.e. a sample. Parts of it are not the way you would do a "real" application.
Some improvements are:
 - remove all hard coded addresses from the code as they reduce the flexibility of how and
   where the application can be run. We leave this to the astute reader to refine the sample into a real-world app.
 - handle the akka.remote.EndpointAssociationException in the case of failed Secure Cookie Handshake


* `Akka <http://akka.io/>`_
* `SBT <http://https://github.com/harrah/xsbt/wiki/>`_
