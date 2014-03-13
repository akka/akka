##########################
The Obligatory Hello World
##########################

The actor based version of the tough problem of printing a
well-known greeting to the console is introduced in a `Typesafe Activator <http://www.typesafe.com/platform/getstarted>`_
tutorial named `Akka Main in Java <http://www.typesafe.com/activator/template/akka-sample-main-java>`_.

The tutorial illustrates the generic launcher class :class:`akka.Main` which expects only
one command line argument: the class name of the application’s main actor. This
main method will then create the infrastructure needed for running the actors,
start the given main actor and arrange for the whole application to shut down
once the main actor terminates.

There is also another `Typesafe Activator <http://www.typesafe.com/platform/getstarted>`_
tutorial in the same problem domain that is named `Hello Akka! <http://www.typesafe.com/activator/template/hello-akka>`_.
It describes the basics of Akka in more depth. 

