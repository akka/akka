/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import java.util.Random;

public class JLookupApp {
    public static void main(String[] args) {
        JLookupApplication app = new JLookupApplication();
        System.out.println("Started Lookup Application");
        Random r = new Random();
        while (true) {
            if (r.nextInt(100) % 2 == 0) {
                app.doSomething(new Op.Add(r.nextInt(100), r.nextInt(100)));    
            } else {
                app.doSomething(new Op.Subtract(r.nextInt(100), r.nextInt(100)));
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {               
            }
        }
    }
}
