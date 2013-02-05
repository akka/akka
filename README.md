akka-osgi-sample   :  Clustered DiningHakker
================
This project may be used to test akka bundles in OSGi Frameworks. The build tool (sbt for the moment) provide scripts to run in an OSGi Framework (Karaf only for the moment) a version of the DiningHakkers that runs on several nodes unsing the akka-cluster module.

## Bundle overview

This project provides three Osgi Bundles
 - api  providing an API for the Service exposed by the core and used by the command
 - core  implementing the whole logic: clustered connections, Hakkers, ChopSticks. It finally provide an ActorRef of one created Hakker
 - command use a service to get a Hakker (ActorRef) with its position around the table

## How to use it

### Setup with sbt
just run:
```bash
sbt clean
sbt package
sbt osgi-bundle
sbt osgi-prepare
```

### Setup with Maven
```bash
mvn clean package
```

### Personalize and run
From this point, you may change the created bundles if you want to test other bundles. Afterwards, launch the OSGi Framework using
``./osgi-run.sh``


Then try to restart some bundles, to test the stability of the bundles:

``list`` to get the list of the bundles
``restart #bundle_number`` to restart the bundle using its ID
``exit`` or CTRL-D to exit the Karaf console

Depending on the akka version you're using, you may need to modify the core bundle when deploying on a second machine, to set it's akka.remote.netty.hostname in the application.conf.

The configuration is set to start the fist node on one machine and to start other nodes on vm running on the same machine for tests (with vmware, ip of the host : 192.168.229.1 and first vm ip would be 192.168.229.128, but it may differ according to your configuration.
