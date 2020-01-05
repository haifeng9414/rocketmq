package com.dhf.study;

import org.junit.Test;

public class AppTest {
    interface A {

    }
    class B {
        A t(A a) {
            return a;
        }
    }

    @Test
    public void shouldAnswerWithTrue() {
        System.out.println(AppTest.class.getName());
        System.out.println(AppTest.class.getCanonicalName());
        System.out.println(B.class.getName());
        System.out.println(B.class.getCanonicalName());

        System.out.println(new AppTest().new B().t(new A() {}).getClass().getName());
        System.out.println(new AppTest().new B().t(new A() {}).getClass().getCanonicalName());
        System.out.println(new AppTest().new B().t(new A() {}).getClass().getName());
        System.out.println(new AppTest().new B().t(new A() {}).getClass().getCanonicalName());
    }
}
