/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import java.util.Random;

public class JCreationApp {
    public static void main(String[] args) {
        JCreationApplication app = new JCreationApplication();
        System.out.println("Started Creation Application");
        Random r = new Random();
        while (true) {
            if (r.nextInt(100) % 2 == 0) {
                app.doSomething(new Op.Multiply(r.nextInt(100), r.nextInt(100)));
            } else {
                app.doSomething(new Op.Divide(r.nextInt(10000), r.nextInt(99) + 1));
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
            }
        }
    }
}
