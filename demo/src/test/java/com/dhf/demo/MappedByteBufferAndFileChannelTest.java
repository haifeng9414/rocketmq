package com.dhf.demo;

import org.junit.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;

public class MappedByteBufferAndFileChannelTest {
    /**
     * 测试直接向FileChannel写入的数据能否通过FileChannel创建出来的MappedByteBuffer获取到
     *
     * 结论：能
     */
    @Test
    public void test() throws IOException {
        final File file = new File(String.format("%s%s%s", System.getProperty("user.home"), File.separator, "MappedByteBufferAndFileChannelTest.tmp"));
        final int fileSize = 64; // 64bytes

        if (file.exists() && file.delete() && file.createNewFile()) {
            System.out.println("初始化文件: " + file.getName());
        } else if (!file.exists() && file.createNewFile()) {
            System.out.println("初始化文件: " + file.getName());
        } else {
            System.out.println("初始化文件异常");
        }

        FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();
        // 内存映射
        MappedByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);

        String message = "test message";
        final ByteBuffer allocate = ByteBuffer.allocate(4 + message.getBytes().length);

        // 写数据到fileChannel
        allocate.putInt(message.getBytes().length);
        allocate.put(message.getBytes(Charset.defaultCharset()));
        allocate.flip();
        fileChannel.write(allocate);

        readFromFileChannel(fileChannel, 0, 4 + message.getBytes().length);
        readFromMappedByteBuffer(mappedByteBuffer);

        fileChannel.close();
    }

    private void readFromFileChannel(FileChannel fileChannel, int position, int length) throws IOException {
        final ByteBuffer readBuffer = ByteBuffer.allocate(length);
        fileChannel.position(position);
        fileChannel.read(readBuffer);

        readBuffer.flip();
        final int size = readBuffer.getInt();
        byte[] bytes = new byte[size];
        readBuffer.get(bytes, 0, size);
        System.out.println("read from file channel: " + new String(bytes, Charset.defaultCharset()));
    }

    private void readFromMappedByteBuffer(MappedByteBuffer mappedByteBuffer) {
        final int size = mappedByteBuffer.getInt();
        byte[] bytes = new byte[size];
        mappedByteBuffer.get(bytes, 0, size);
        System.out.println("read from file channel: " + new String(bytes, Charset.defaultCharset()));
    }
}
