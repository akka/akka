Actor References, Paths and Addresses
=====================================

This chapter describes how actors are identified and located within a possibly
distributed actor system. It ties into the central idea that actor systems form
intrinsic supervision hierarchies as well as that communication between actors
is transparent with respect to their placement across multiple network nodes.

What is an Actor Reference?
---------------------------

An actor reference is a subtype of :class:`ActorRef`, whose foremost purpose is 
to support sending messages to the actor it represents. Each actor has access 
to its canonical (local) reference through the :meth:`self` field; this 
reference is also included as sender reference by default for all messages sent 
to other actors. Conversely, during message processing the actor has access to 
a reference representing the sender of the current message through the 
:meth:`sender` field.

There are several different types of actor references that are supported 
depending on the configuration of the actor system:

- Purely local actor references are used by actor systems which are not 
  configured to support networking functions. These actor references cannot 
  ever be sent across a network connection while retaining their functionality.
- Local actor references when remoting is enabled are used by actor systems 
  which support networking functions for those references which represent 
  actors within the same JVM. In order to be recognizable also when sent to 
  other network nodes, these references include protocol and remote addressing 
  information.
- Remote actor references represent actors which are reachable using remote 
  communication, i.e. sending messages to them will serialize the messages 
  transparently and send them to the other JVM.
- **(Future Extension)** Cluster actor references represent clustered actor 
  services which may be replicated, migrated or load-balanced across multiple 
  cluster nodes. As such they are virtual names which the cluster service 
  translates into local or remote actor references as appropriate.
- Unresolved actor references are obtained by querying an actor system and may 
  potentially represent one or more actors.

How are Actor References obtained?
----------------------------------

An actor system is typically started by creating actors beneath the guardian 
actor using the :meth:`ActorSystem.actorOf` method, which returns a reference 
to the newly created actor. Each actor has direct access to references for its 
parent, itself and its children. These references may be sent within messages 
to other actors, enabling those to reply directly.

Looking up Actors by Absolute Path
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In addition, actor references may be looked up using the 
:meth:`ActorSystem.actorFor` method, which returns an unresolved actor 
reference. Sending messages to such a reference will traverse the actor 
hierarchy of the actor system from top to bottom by passing messages from 
supervisor to child until either the target is reached or failure is certain 
(i.e. a name in the path does not exist). Since this process takes time, 
replies from the found actors—which include their sender reference—should be 
used to replace them by direct actor references.

Looking up Actors by Relative Path
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The third method for obtaining actor references is 
:meth:`ActorContext.actorFor`, which is available inside any actor as 
``context.actorFor``. This yields an unresolved actor reference much like its 
twin on :class:`ActorSystem`, but instead of looking up the path starting from 
the root of the actor tree it starts out on the current actor. Path elements 
consisting of two dots (``".."``) may be used to access the parent actor and 
other names are interpreted as globbing patterns. You can for example send a 
message to all your siblings by using a path like ``"../*"`` (this will include 
yourself).

What is an Actor Path?
----------------------

Since actors are created in a strictly hierarchical fashion, there exists a 
unique sequence of actor names given by following the tree of actors up until 
the root of the actor system. This sequence can be seen as enclosing folders in 
a file system, hence we adopted the name “path” to refer to it.

Each actor path has an address component, describing the protocol and location 
by which the corresponding actor is reachable, followed by the names of the 
actors in the hierarchy from the root up. Examples are::

  "jvm://my-system/app/service-a/worker1"
  "akka://serv.example.com:5678/app/service-b"

where the first represents a purely local actor reference while the second 
stands for a remote actor reference. Each actor has access to its path, and you 
may construct paths and pass them to :meth:`ActorSystem.actorFor` in order to 
obtain a corresponding actor reference.

One important aspect is that actor paths never span multiple actor systems or 
JVMs. An actor path always represents the physical location of an actor. This 
means that the supervision hierarchy and the path hierarchy of an actor may 
diverge if one of its ancestors is remotely supervised.

The Interplay with Remote Deployment
------------------------------------

When an actor creates a child, the actor system’s deployer will decide whether 
the new actor resides in the same JVM or on another node. In the second case, 
creation of the actor will be triggered via a network connection to happen in a 
different JVM and consequently within a different actor system. The remote 
system will place the new actor below a special path reserved for this purpose 
and the supervisor of the new actor will be a remote actor reference 
(representing that actor which triggered its creation). In this case, 
:meth:`parent` (the supervisor reference) and :meth:`context.path.parent` (the 
parent node in the actor’s path) do not represent the same actor. However, 
looking up the child’s name within the supervisor will find it on the remote 
node, preserving logical structure e.g. when sending to an unresolved actor 
reference.

What is the Address part used for?
----------------------------------

When sending an actor reference across the network, it is represented by its 
path. Hence, the path must fully encode all information necessary to send 
messages to the underlying actor. This is achieved by encoding protocol, host 
and port in the address part of the path string. When an actor system receives 
an actor path from a remote node, it checks whether that path’s address matches 
the address of this actor system, in which case it will be resolved to the 
actor’s local reference. Otherwise, it will be represented by a remote actor 
reference.

Special Paths used by Akka
--------------------------

At the root of the path hierarchy resides the root guardian above which all 
other actors are found. The next level consists of the following:

- ``"/app"`` is the guardian actor for all user-created top-level actors; 
  actors created using :meth:`ActorSystem.actorOf` are found at the next level.
- ``"/sys"`` is the guardian actor for all system-created top-level actors, 
  e.g. logging listeners or actors automatically deployed by configuration at 
  the start of the actor system.
- ``"/nul"`` is the dead letter actor, which is where all messages sent to 
  stopped or non-existing actors are re-routed.
- ``"/tmp"`` is the guardian for all short-lived system-created actors, e.g.  
  those which are used in the implementation of :meth:`ActorRef.ask`.
- ``"/remote"`` is an artificial path below which all actors reside whose 
  supervisors are remote actor references

