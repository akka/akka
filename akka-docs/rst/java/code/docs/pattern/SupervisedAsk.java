public class SupervisedAsk {

    private static class AskParam {
        Props props;
        Object message;
        Timeout timeout;
        AskParam(Props props, Object message, Timeout timeout) {
            this.props = props;
            this.message = message;
            this.timeout = timeout;
        }
    }

    private static class AskTimeout {}
    
    public static class AskSupervisorCreator extends UntypedActor {

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof AskParam) {
                ActorRef supervisor = getContext().actorOf(
					Props.apply(AskSupervisor.class));
                supervisor.forward(message, getContext());
            } else {
                unhandled(message);
            }
        }
    }

    public static class AskSupervisor extends UntypedActor {
        private ActorRef targetActor;
        private ActorRef caller;
        private AskParam askParam;
        private Cancellable timeoutMessage;

        @Override
        public SupervisorStrategy supervisorStrategy() {
            return new OneForOneStrategy(0, Duration.Zero(),
				new Function<Throwable, Directive>() {
                public Directive apply(Throwable cause) {
                    caller.tell(new Status.Failure(cause));
                    return SupervisorStrategy.stop();
                }
            });
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof AskParam) {
                askParam = (AskParam) message;
                caller = getSender();
                targetActor = getContext().actorOf(askParam.props);
                getContext().watch(targetActor);
                targetActor.forward(askParam.message, getContext());
                Scheduler scheduler = getContext().system().scheduler();
                timeoutMessage = scheduler.scheduleOnce(askParam.timeout.duration(), self(), new AskTimeout());
            } else if (message instanceof Terminated) {
                Throwable ex = new ActorKilledException("Target actor terminated.");
                caller.tell(new Status.Failure(ex));
                timeoutMessage.cancel();
                getContext().stop(self());
            } else if (message instanceof AskTimeout) {
                Throwable ex = new TimeoutException("Target actor timed out after " + askParam.timeout.toString());
                caller.tell(new Status.Failure(ex));
                getContext().stop(self());         
            } else
                unhandled(message);
        }
    }

    public static Future<Object> askOf(ActorRef supervisorCreator, 
		Props props, Object message, Timeout timeout) {
        AskParam param = new AskParam(props, message, timeout);
        return Patterns.ask(supervisorCreator, param, timeout);
    }

    synchronized public static ActorRef createSupervisorCreator(ActorRefFactory factory) {
        return factory.actorOf(Props.apply(AskSupervisorCreator.class));
    }
}

// example usage
try {
	ActorRef supervisorCreator = SupervisedAsk.createSupervisorCreator(actorSystem);
	Future<Object> finished = SupervisedAsk.askOf(supervisorCreator, 
		Props.apply(SomeActor.class), message, timeout);
	SomeResult result = (SomeResult) Await.result(finished, timeout.duration());
} catch (Exception e) {
	// exception propagated by supervision
}