Ants
====

Ants is written by Peter Vlugter.

Ants is based on the Clojure [ants simulation][ants.clj] by Rich Hickey, and ported to Scala using [Akka][akka] and [Spde][spde].

[ants.clj]:http://clojure.googlegroups.com/web/ants.clj
[akka]:http://akkasource.org
[spde]:http://technically.us/spde/


Requirements
------------

To build and run Ants you need [Simple Build Tool][sbt] (sbt).

[sbt]: http://code.google.com/p/simple-build-tool/


Running
-------

First time, 'sbt update' to get dependencies, then to run Ants use 'sbt run'. 
Here is an example. First type 'sbt' to start SBT interactively, the run 'update' and 'run': 

% sbt
> update
> run


Notice
------

Ants is based on the Clojure ants simulation by Rich Hickey.

Copyright (c) Rich Hickey. All rights reserved.
The use and distribution terms for this software are covered by the
Common Public License 1.0 ([http://opensource.org/licenses/cpl1.0.php][cpl])
which can be found in the file cpl.txt at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.

[cpl]: http://opensource.org/licenses/cpl1.0.php