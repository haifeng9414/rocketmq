package com.dhf.demo;

import org.apache.rocketmq.common.UtilAll;
import org.junit.Test;

import java.io.File;

public class UtilAllTest {
    @Test
    public void diskPartitionSpaceUsedPercentTest() {
        System.out.println(UtilAll.getDiskPartitionSpaceUsedPercent(System.getProperty("user.home")));
        System.out.println(UtilAll.getDiskPartitionSpaceUsedPercent(System.getProperty("user.home") + File.separator + "store"));
    }
}
