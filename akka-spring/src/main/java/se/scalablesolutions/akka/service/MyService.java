package se.scalablesolutions.akka.service;

import org.springframework.transaction.annotation.Transactional;


//import se.scalablesolutions.akka.annotation.oneway;

public class MyService {

	public Integer getNumbers(int aTestNumber, String aText) {
		System.out.println("MyService : " + Thread.currentThread());
		return new Integer(aTestNumber);
	}

	//@oneway
	public void calculate() {
		for (int i = 1; i < 10000; i++) {
			System.out.println("i=" + i);
		}
	}

}

