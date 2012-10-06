public class JavaAsk extends UntypedActor {
    private ActorRef targetActor;
    private ActorRef caller;
    
    private static class AskParam {
        Props props;
        Object message;
        AskParam(Props props, Object message) {
            this.props = props;
            this.message = message;
        }
    }
    
    @Override
    public SupervisorStrategy supervisorStrategy() {
        return new OneForOneStrategy(0, Duration.Zero(), new Function<Throwable, Directive>() {
            public Directive apply(Throwable cause) {
                caller.tell(new Status.Failure(cause));
                return SupervisorStrategy.stop();
            }
        });
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof AskParam) {
            AskParam param = (AskParam) message;
            caller = getSender();
            targetActor = getContext().actorOf(param.props);
            targetActor.forward(param.message, getContext());
        } else
            unhandled(message);
    }

    public static void ask(ActorSystem system, Props props, Object message, Timeout timeout) throws Exception {
        ActorRef javaAsk = system.actorOf(Props.apply(JavaAsk.class));
        try {
            AskParam param = new AskParam(props, message);
            Future<Object> finished = Patterns.ask(javaAsk, param, timeout);
            Await.result(finished, timeout.duration());
        } finally {
            system.stop(javaAsk);
        }
    }
}