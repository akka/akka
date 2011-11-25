Ants
====

Ants is written by Peter Vlugter.

Ants is roughly based on the Clojure [ants simulation][ants.clj] by Rich Hickey, and ported to Scala using [Akka][akka] and [Spde][spde].

Requirements
------------

To build and run Ants you need [Simple Build Tool][sbt] (sbt).

Running
-------

First time, 'sbt update' to get dependencies, then to run Ants use 'sbt run'. 
Here is an example. First type 'sbt' to start SBT interactively, the run 'update' and 'run': 
> cd $AKKA_HOME

> % sbt

> > update

> > project akka-sample-ants

> > run


Notice
------

Ants is roughly based on the Clojure ants simulation by Rich Hickey.

Copyright (c) Rich Hickey. All rights reserved.
The use and distribution terms for this software are covered by the
Common Public License 1.0 ([http://opensource.org/licenses/cpl1.0.php][cpl])
which can be found in the file cpl.txt at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.

[ants.clj]: http://clojure.googlegroups.com/web/ants.clj
[akka]: http://akka.io
[spde]: http://technically.us/spde/
[sbt]: http://code.google.com/p/simple-build-tool/
[cpl]: http://opensource.org/licenses/cpl1.0.php