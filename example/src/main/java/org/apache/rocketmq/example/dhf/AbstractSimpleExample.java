package org.apache.rocketmq.example.dhf;

import org.apache.rocketmq.remoting.common.RemotingHelper;

import java.io.UnsupportedEncodingException;

public class AbstractSimpleExample {
    protected final static String GROUP_NAME = "unique_group_name";
    protected final static String TOPIC = "MY_TOPIC";
    protected final static String TAG = "MY_TAG";
    protected final static String NAME_SERVER = "localhost:9876";

    protected static byte[] getMessage(String message) throws UnsupportedEncodingException {
        return message.getBytes(RemotingHelper.DEFAULT_CHARSET);
    }
}
