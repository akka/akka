<!--- #actors-and-exceptions --->
## Actors and exceptions

It can happen that while a message is being processed by an actor, that some
kind of exception is thrown, e.g. a database exception.

### What happens to the Message

If an exception is thrown while a message is being processed (i.e. taken out of
its mailbox and handed over to the current behavior), then this message will be
lost. It is important to understand that it is not put back on the mailbox. So
if you want to retry processing of a message, you need to deal with it yourself
by catching the exception and retry your flow. Make sure that you put a bound
on the number of retries since you don't want a system to livelock (so
consuming a lot of cpu cycles without making progress).

### What happens to the mailbox

If an exception is thrown while a message is being processed, nothing happens to
the mailbox. If the actor is restarted, the same mailbox will be there. So all
messages on that mailbox will be there as well.

### What happens to the actor

If code within an actor throws an exception, that actor is suspended and the
supervision process is started. Depending on the
supervisor’s decision the actor is resumed (as if nothing happened), restarted
(wiping out its internal state and starting from scratch) or terminated.
<!--- #actors-and-exceptions --->