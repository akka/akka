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

* CalculatorApplication : the actor system performing the number crunching
* LookupApplication     : illustrates how to look up an actor on a remote node and and how communicate with that actor
* CreationApplication   : illustrates how to create an actor on a remote node and how to communicate with that actor

The CalculatorApplication contains an actor, SimpleCalculatorActor, which can handle simple math operations such as
addition and subtraction. This actor is looked up and used from the LookupApplication.

The CreationApplication wants to use more "advanced" mathematical operations, such as multiplication and division,
but as the CalculatorApplication does not have any actor that can perform those type of calculations the
CreationApplication has to remote deploy an actor that can (which in our case is AdvancedCalculatorActor).
So this actor is deployed, over the network, onto the CalculatorApplication actor system and thereafter the
CreationApplication will send messages to it.

It is important to point out that as the actor system run on different ports it is possible to run all three in parallel.
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
    [INFO] [01/14/2013 14:45:23.055] [run-main] [Remoting
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
    [INFO] [01/14/2013 15:03:10.604] [run-main] [Remoting] Starting remoting
    Started Lookup Application
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

Notice
------

The sample application is just that, i.e. a sample. Parts of it are not the way you would do a "real" application.
Some improvements are to remove all hard coded addresses from the code as they reduce the flexibility of how and
where the application can be run. We leave this to the astute reader to refine the sample into a real-world app.

* `Akka <http://akka.io/>`_
* `SBT <http://https://github.com/harrah/xsbt/wiki/>`_
