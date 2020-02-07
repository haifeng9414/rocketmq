package com.dhf.demo;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ByteBufferTest {
    @Test
    public void sliceTest() {
        int capacity = 5;
        try {
            ByteBuffer bb1 = ByteBuffer.allocate(capacity);
            bb1.put((byte) 10);
            bb1.put((byte) 20);

            System.out.println("Original ByteBuffer: " + Arrays.toString(bb1.array()));
            System.out.println("position: " + bb1.position());
            System.out.println("capacity: " + bb1.capacity());

            ByteBuffer bb2 = bb1.slice();
            System.out.println("shared subsequance ByteBuffer: " + Arrays.toString(bb2.array()));

            System.out.println("position: " + bb2.position());
            System.out.println("capacity: " + bb2.capacity());

            System.out.println("put data to shared subsequance ByteBuffer");
            bb2.put((byte) 30);

            System.out.println("Original ByteBuffer: " + Arrays.toString(bb1.array()));
            System.out.println("position: " + bb1.position());
            System.out.println("capacity: " + bb1.capacity());

            System.out.println("shared subsequance ByteBuffer: " + Arrays.toString(bb2.array()));

            System.out.println("position: " + bb2.position());
            System.out.println("capacity: " + bb2.capacity());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
