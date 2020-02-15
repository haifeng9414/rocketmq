package com.dhf.demo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class TmpTest {
    public static void main(String[] args) throws IOException {
        final File file = new File("/var/folders/6j/qxxmf1sj57v4tzs_f6zd99h80000gn/T/file0");

        FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();

        final ByteBuffer allocate = ByteBuffer.allocate(1024);
        System.out.println("read: " + fileChannel.read(allocate));

        allocate.flip();
        System.out.println(Arrays.toString(allocate.array()));

        fileChannel.close();
    }
}
