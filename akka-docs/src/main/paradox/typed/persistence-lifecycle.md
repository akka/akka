# Persistence - lifecycle

# Four behaviors of EventSourcedBehavior
 
1. RequestingRecoveryPermit: Requests a permit to start replaying this actor. 
This avoids hammering the journal with too many concurrently replaying actors.
 
2. ReplayingSnapshot: In this behavior the recovery process is initiated. 
We try to obtain a snapshot from the configured snapshot store, and if it exists, we use it instead of the initial `emptyState`.
If an event replay fails, due to re-processing or the snapshot recovery timeout has been met,
the error is logged and the actor is stopped. 
 
3. ReplayingEvents: In this behavior event replay finally begins, starting from the last applied sequence number
(i.e. the one up-until-which the snapshot recovery has brought us). Once recovery is completed successfully, the state becomes running.
  
4. Running (or, 'Final'): In this state recovery has completed successfully, stashed messages are flushed
and control is given to the handlers to drive the behavior from there.
Handling incoming commands continues, as well as persisting new events as dictated by the handlers.
This behavior operates in two phases (also behaviors):
    - HandlingCommands - where the command handler is invoked for incoming commands
    - PersistingEvents - where incoming commands are stashed until persistence completes
 