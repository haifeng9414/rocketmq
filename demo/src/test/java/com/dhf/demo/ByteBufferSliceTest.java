package com.dhf.demo;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ByteBufferSliceTest {
    /**
     * 测试slice方法创建出来的ByteBuffer对象的position和capacity属性值及该对象对原ByteBuffer对象的影响
     *
     * 结果是：当bb1调用slice方法创建出bb2后，bb1和bb2的position和capacity属性互不影响，且bb2的position初始等于0，capacity
     * 等于bb1的剩余容量。将数据写入bb2会影响bb1的数据但不影响bb1的position和capacity属性
     */
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
