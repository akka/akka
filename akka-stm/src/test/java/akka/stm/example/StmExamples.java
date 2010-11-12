package akka.stm.example;

public class StmExamples {
    public static void main(String[] args) {
        System.out.println();
        System.out.println("STM examples");
        System.out.println();

        CounterExample.main(args);
        RefExample.main(args);
        TransactionFactoryExample.main(args);
        TransactionalMapExample.main(args);
        TransactionalVectorExample.main(args);
    }
}
