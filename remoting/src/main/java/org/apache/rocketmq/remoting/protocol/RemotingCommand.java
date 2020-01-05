/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.remoting.protocol;

import com.alibaba.fastjson.annotation.JSONField;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.remoting.CommandCustomHeader;
import org.apache.rocketmq.remoting.annotation.CFNotNull;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;

public class RemotingCommand {
    // 序列化类型，默认JSON，也可以是ROCKETMQ
    public static final String SERIALIZE_TYPE_PROPERTY = "rocketmq.serialize.type";
    // 用于设置序列化类型的环境变量，默认为空
    public static final String SERIALIZE_TYPE_ENV = "ROCKETMQ_SERIALIZE_TYPE";
    // 系统参数中保存当前RocketMQ版本的属性key
    public static final String REMOTING_VERSION_KEY = "rocketmq.remoting.version";
    private static final InternalLogger log = InternalLoggerFactory.getLogger(RemotingHelper.ROCKETMQ_REMOTING);
    // 用于设置RemotingCommand的类型，看markResponseType()实现
    private static final int RPC_TYPE = 0; // 0, REQUEST_COMMAND
    // 用于设置RemotingCommand的类型，看markOnewayRPC()实现
    private static final int RPC_ONEWAY = 1; // 0, RPC
    // 缓存解析过属性列表的CommandCustomHeader类的解析结果
    private static final Map<Class<? extends CommandCustomHeader>, Field[]> CLASS_HASH_MAP =
        new HashMap<Class<? extends CommandCustomHeader>, Field[]>();
    /*
     缓存类的getCanonicalName方法返回值，普通类class.getCanonicalName方法和class.getName结果一样，但是：
     class为内部类时
     class.getCanonicalName方法返回java.util.AbstractMap.SimpleEntry的形式，
     class.getName方法返回java.util.AbstractMap$SimpleEntry的形式，

     class为匿名内部类时
     class.getCanonicalName方法返回null，
     class.getName方法返回com.dhf.study.AppTest$1的形式
     */
    private static final Map<Class, String> CANONICAL_NAME_CACHE = new HashMap<Class, String>();
    // 1, Oneway
    // 1, RESPONSE_COMMAND
    // 缓存解析过的Field对象是否可以为空的结果，解析规则是判断属性上是否带有CFNotNull注解
    private static final Map<Field, Boolean> NULLABLE_FIELD_CACHE = new HashMap<Field, Boolean>();
    // 保存各种常见数据类型的名称，在decodeCommandCustomHeader方法中会用到
    // java.lang.String
    private static final String STRING_CANONICAL_NAME = String.class.getCanonicalName();
    // java.lang.Double
    private static final String DOUBLE_CANONICAL_NAME_1 = Double.class.getCanonicalName();
    // double
    private static final String DOUBLE_CANONICAL_NAME_2 = double.class.getCanonicalName();
    // java.lang.Integer
    private static final String INTEGER_CANONICAL_NAME_1 = Integer.class.getCanonicalName();
    // int
    private static final String INTEGER_CANONICAL_NAME_2 = int.class.getCanonicalName();
    // java.lang.Long
    private static final String LONG_CANONICAL_NAME_1 = Long.class.getCanonicalName();
    // long
    private static final String LONG_CANONICAL_NAME_2 = long.class.getCanonicalName();
    // java.lang.Boolean
    private static final String BOOLEAN_CANONICAL_NAME_1 = Boolean.class.getCanonicalName();
    // boolean
    private static final String BOOLEAN_CANONICAL_NAME_2 = boolean.class.getCanonicalName();
    // 保存从环境变量获取到的RocketMQ版本，创建RemotingCommand时将该值设置到RemotingCommand的version属性，这样不用每次创建
    // RemotingCommand对象都读取一遍环境变量
    private static volatile int configVersion = -1;
    // RocketMQ中对于一个客户端发出的每个请求，都有一个请求id，该id在客户端内唯一
    private static AtomicInteger requestId = new AtomicInteger(0);

    // 序列化类型，默认JSON
    private static SerializeType serializeTypeConfigInThisServer = SerializeType.JSON;

    static {
        // 如果环境变量中设置了序列化类型则解析
        final String protocol = System.getProperty(SERIALIZE_TYPE_PROPERTY, System.getenv(SERIALIZE_TYPE_ENV));
        if (!isBlank(protocol)) {
            try {
                serializeTypeConfigInThisServer = SerializeType.valueOf(protocol);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("parser specified protocol error. protocol=" + protocol, e);
            }
        }
    }

    // 如果是请求，则表示请求的类型，所有的请求类型对应的值定义在RequestCode类中。如果是响应，则表示响应的类型，所有的响应类型对应的值
    // 定义在ResponseCode类中，0表示成功，非0对应某种错误。
    private int code;
    // 请求中表示客户端实现的语言/响应中表示服务器实现的语言
    private LanguageCode language = LanguageCode.JAVA;
    // 请求中表示客户端程序版本/响应中表示服务器程序版本。知道对方的版本号，响应方可以以此做兼容老版本等的特殊操作
    private int version = 0;
    // 请求中表示请求id/响应中等于请求id
    private int opaque = requestId.getAndIncrement();
    // 标志位，表示RemotingCommand对应的类型，如请求、响应、oneway
    private int flag = 0;
    // 请求中表示请求的自定义文本信息/响应中表示错误信息
    private String remark;
    // 请求中表示请求的自定义字段/响应中表示响应的自定义字段，每个请求和响应都有自己自定义的字段，传输时这些字段保存在该属性
    private HashMap<String, String> extFields;
    // extFields只是自定义字段的kv表示形式，在传输前，请求或响应的的自定义字段实际上都在一个对象中，该对象就是CommandCustomHeader
    // 接口的实现类的对象。用对象表示自定义字段便于操作和理解。当传输时，会将请求或响应操作的customHeader对象的field放到extFields
    // 中，而customHeader就没必要传输了，所以该属性有transient关键字
    private transient CommandCustomHeader customHeader;

    // RemotingCommand对象的序列化类型
    private SerializeType serializeTypeCurrentRPC = serializeTypeConfigInThisServer;

    // body为消息主体的字节数组，可能为空，既然是字节数组类型的，就没必要序列化了，传输时直接使用即可，具体编码过程在encode方法
    private transient byte[] body;

    protected RemotingCommand() {
    }

    public static RemotingCommand createRequestCommand(int code, CommandCustomHeader customHeader) {
        RemotingCommand cmd = new RemotingCommand();
        cmd.setCode(code);
        cmd.customHeader = customHeader;
        // 从环境变量中获取RocketMQ的版本设置到cmd的version属性
        setCmdVersion(cmd);
        return cmd;
    }

    private static void setCmdVersion(RemotingCommand cmd) {
        // 如果已经存在值了就直接使用
        if (configVersion >= 0) {
            cmd.setVersion(configVersion);
        } else {
            // 否则从环境变量中获取RocketMQ版本
            String v = System.getProperty(REMOTING_VERSION_KEY);
            if (v != null) {
                int value = Integer.parseInt(v);
                cmd.setVersion(value);
                configVersion = value;
            }
        }
    }

    public static RemotingCommand createResponseCommand(Class<? extends CommandCustomHeader> classHeader) {
        // 工厂方法，创建一个响应类型的RemotingCommand，customHeader属性为根据传入的类型反射创建的对象
        return createResponseCommand(RemotingSysResponseCode.SYSTEM_ERROR, "not set any response code", classHeader);
    }

    // 工厂方法，创建响应类型的RemotingCommand
    public static RemotingCommand createResponseCommand(int code, String remark,
        Class<? extends CommandCustomHeader> classHeader) {
        RemotingCommand cmd = new RemotingCommand();
        cmd.markResponseType();
        cmd.setCode(code);
        cmd.setRemark(remark);
        setCmdVersion(cmd);

        // 反射创建customHeader
        if (classHeader != null) {
            try {
                CommandCustomHeader objectHeader = classHeader.newInstance();
                cmd.customHeader = objectHeader;
            } catch (InstantiationException e) {
                return null;
            } catch (IllegalAccessException e) {
                return null;
            }
        }

        return cmd;
    }

    // 工厂方法，创建响应类型的RemotingCommand
    public static RemotingCommand createResponseCommand(int code, String remark) {
        return createResponseCommand(code, remark, null);
    }

    // 解码RemotingCommand
    public static RemotingCommand decode(final byte[] array) {
        // 使用传入的字节数组创建一个ByteBuffer
        ByteBuffer byteBuffer = ByteBuffer.wrap(array);
        return decode(byteBuffer);
    }

    // 解码字节数组为RemotingCommand，看该方法的实现需要先理解encode方法的编码过程
    public static RemotingCommand decode(final ByteBuffer byteBuffer) {
        // 获取字节数组实际内容的大小
        int length = byteBuffer.limit();
        // 这里获取的int值实际上是encode方法中说的第二部分的值，第一部分的值在decode方法被调用时已经忽略掉了，看NettyDecoder
        // 类的实现
        int oriHeaderLen = byteBuffer.getInt();
        // 获取RemotingCommand对象序列化后的字节数组的长度
        int headerLength = getHeaderLength(oriHeaderLen);

        byte[] headerData = new byte[headerLength];
        // 获取RemotingCommand对象序列化后的字节数组
        byteBuffer.get(headerData);

        // 解码RemotingCommand对象，getProtocolType方法返回序列化类型
        RemotingCommand cmd = headerDecode(headerData, getProtocolType(oriHeaderLen));

        // 获取消息主体的长度并读取
        int bodyLength = length - 4 - headerLength;
        byte[] bodyData = null;
        if (bodyLength > 0) {
            bodyData = new byte[bodyLength];
            byteBuffer.get(bodyData);
        }
        cmd.body = bodyData;

        return cmd;
    }

    public static int getHeaderLength(int length) {
        // length是传输协议的第二部分的值，看markProtocolType方法的实现可知，该值的最高位字节表示序列化类型，低3位字节等于header
        // 序列化字节数组的长度，通过下面的语句就能返回
        return length & 0xFFFFFF;
    }

    private static RemotingCommand headerDecode(byte[] headerData, SerializeType type) {
        switch (type) {
            case JSON:
                // 对于JSON序列化类型，再使用JSON反序列化即可
                RemotingCommand resultJson = RemotingSerializable.decode(headerData, RemotingCommand.class);
                resultJson.setSerializeTypeCurrentRPC(type);
                return resultJson;
            case ROCKETMQ:
                RemotingCommand resultRMQ = RocketMQSerializable.rocketMQProtocolDecode(headerData);
                resultRMQ.setSerializeTypeCurrentRPC(type);
                return resultRMQ;
            default:
                break;
        }

        return null;
    }

    public static SerializeType getProtocolType(int source) {
        // 和getHeaderLength方法一个原理
        return SerializeType.valueOf((byte) ((source >> 24) & 0xFF));
    }

    // 用AtomicInteger递增创建请求id
    public static int createNewRequestId() {
        return requestId.incrementAndGet();
    }

    public static SerializeType getSerializeTypeConfigInThisServer() {
        return serializeTypeConfigInThisServer;
    }

    // 如果参数为空字符串或全是空格则返回true
    private static boolean isBlank(String str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // source为RemotingCommand对象的序列化字节数组的长度
    public static byte[] markProtocolType(int source, SerializeType type) {
        byte[] result = new byte[4];

        // 获取序列化类型对应的code，0表示JSON，1表示ROCKETMQ
        result[0] = type.getCode();
        // source是序列化字节数组的长度，所以理论上不会太大，所以整型source的4个字节ABCD中A就是0x0，markProtocolType方法
        // 利用这个推断使用4个字节同时保存source的值和type的值，下面的3行很好理解，(source >> 16) & 0xFF的结果是source的
        // ABCD四个字节中的AB的值，再强转为byte后结果就是B，同理(byte) ((source >> 8) & 0xFF)就是C，(byte) (source & 0xFF)
        // 就是D，所以最后返回的结果中第0个字节表示序列化类型，后3个字节等于source的ABCD中的BCD，也就是说markProtocolType方法
        // 用表示序列化类型的字节覆盖了source的最高位字节并返回字节数组
        result[1] = (byte) ((source >> 16) & 0xFF);
        result[2] = (byte) ((source >> 8) & 0xFF);
        result[3] = (byte) (source & 0xFF);
        return result;
    }

    // 设置flag属性标识RemotingCommand类型
    public void markResponseType() {
        int bits = 1 << RPC_TYPE;
        this.flag |= bits;
    }

    public CommandCustomHeader readCustomHeader() {
        return customHeader;
    }

    public void writeCustomHeader(CommandCustomHeader customHeader) {
        this.customHeader = customHeader;
    }

    // 反射创建一个classHeader类型的对象，根据当前RemotingCommand对象的extFields属性中的值为对象赋值，只支持基本类型的属性赋值。
    public CommandCustomHeader decodeCommandCustomHeader(
        Class<? extends CommandCustomHeader> classHeader) throws RemotingCommandException {
        CommandCustomHeader objectHeader;
        try {
            objectHeader = classHeader.newInstance();
        } catch (InstantiationException e) {
            return null;
        } catch (IllegalAccessException e) {
            return null;
        }

        if (this.extFields != null) {

            Field[] fields = getClazzFields(classHeader);
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    String fieldName = field.getName();
                    if (!fieldName.startsWith("this")) {
                        try {
                            String value = this.extFields.get(fieldName);
                            if (null == value) {
                                if (!isFieldNullable(field)) {
                                    throw new RemotingCommandException("the custom field <" + fieldName + "> is null");
                                }
                                continue;
                            }

                            field.setAccessible(true);
                            String type = getCanonicalName(field.getType());
                            Object valueParsed;

                            // 只支持基本类型的属性赋值
                            if (type.equals(STRING_CANONICAL_NAME)) {
                                valueParsed = value;
                            } else if (type.equals(INTEGER_CANONICAL_NAME_1) || type.equals(INTEGER_CANONICAL_NAME_2)) {
                                valueParsed = Integer.parseInt(value);
                            } else if (type.equals(LONG_CANONICAL_NAME_1) || type.equals(LONG_CANONICAL_NAME_2)) {
                                valueParsed = Long.parseLong(value);
                            } else if (type.equals(BOOLEAN_CANONICAL_NAME_1) || type.equals(BOOLEAN_CANONICAL_NAME_2)) {
                                valueParsed = Boolean.parseBoolean(value);
                            } else if (type.equals(DOUBLE_CANONICAL_NAME_1) || type.equals(DOUBLE_CANONICAL_NAME_2)) {
                                valueParsed = Double.parseDouble(value);
                            } else {
                                throw new RemotingCommandException("the custom field <" + fieldName + "> type is not supported");
                            }

                            field.set(objectHeader, valueParsed);

                        } catch (Throwable e) {
                            log.error("Failed field [{}] decoding", fieldName, e);
                        }
                    }
                }
            }

            // 让CommandCustomHeader的具体实现检查属性是否合法
            objectHeader.checkFields();
        }

        return objectHeader;
    }

    // 获取classHeader类的所有属性
    private Field[] getClazzFields(Class<? extends CommandCustomHeader> classHeader) {
        // 先从缓存中获取
        Field[] field = CLASS_HASH_MAP.get(classHeader);

        if (field == null) {
            field = classHeader.getDeclaredFields();
            synchronized (CLASS_HASH_MAP) {
                CLASS_HASH_MAP.put(classHeader, field);
            }
        }
        return field;
    }

    private boolean isFieldNullable(Field field) {
        // 先从缓存中获取
        if (!NULLABLE_FIELD_CACHE.containsKey(field)) {
            // 以是否带有CFNotNull注解为是否可以为空的依据
            Annotation annotation = field.getAnnotation(CFNotNull.class);
            synchronized (NULLABLE_FIELD_CACHE) {
                NULLABLE_FIELD_CACHE.put(field, annotation == null);
            }
        }
        return NULLABLE_FIELD_CACHE.get(field);
    }

    // 获取类名
    private String getCanonicalName(Class clazz) {
        // 先从缓存中获取
        String name = CANONICAL_NAME_CACHE.get(clazz);

        if (name == null) {
            name = clazz.getCanonicalName();
            synchronized (CANONICAL_NAME_CACHE) {
                CANONICAL_NAME_CACHE.put(clazz, name);
            }
        }
        return name;
    }

    /*
    根据该方法的实现，可以发现RocketMQ的传输协议格式：

    length | headerLength & serializeType | header | body
    第一个部分length占4个字节，表示不包括自己后面所有内容的长度
    第二个部分headerLength & serializeType占4个字节，表示了header部分的长度和序列化类型，可以看markProtocolType方法注释
    第三个部分header占用字节数保存在第二个部分，表示RemotingCommand对象序列化的值
    第四个部分为消息主体
     */
    public ByteBuffer encode() {
        // 1> header length size
        // 记录总长度，初始长度为4，因为headLength字段为整型，占4个字节
        int length = 4;

        // 2> header data length
        // 获取RemotingCommand对象本身的序列化字节数组
        byte[] headerData = this.headerEncode();
        // 总长度加上header的长度也就是RemotingCommand对象本身的序列化字节数组
        length += headerData.length;

        // 3> body data length
        // body为消息主体，如果存在body则加上主体的长度
        if (this.body != null) {
            length += body.length;
        }

        // 申请字节数组保存编码结果，数组长度为4 + length是因为总长度字段也是整型，占用4个字节
        ByteBuffer result = ByteBuffer.allocate(4 + length);

        // length
        // 编码结果的第一个部分是headerLength + headerData + body的总长度字段
        result.putInt(length);

        // header length
        // 用4个字节保存headerData.length的值和序列化类型信息
        result.put(markProtocolType(headerData.length, serializeTypeCurrentRPC));

        // header data
        // RemotingCommand对象的序列化值
        result.put(headerData);

        // body data;
        if (this.body != null) {
            // 消息主体
            result.put(this.body);
        }

        /*
        flip方法涉及到buffer中的capacity，position和limit三个概念。其中capacity在读写模式下都是固定的，就是分配的缓冲大小，position
        类似于读写指针，表示当前读/写到什么位置，limit在写模式下表示最多能写入多少数据，此时和capacity相同，在读模式下表示最多能读多少数
        据，此时和缓存中的实际数据大小相同。在写模式下调用flip方法，那么limit就设置为了position当前的值（即当前写了多少数据），position
        会被置为0，以表示读操作从缓存的头开始读。也就是说调用flip之后，读写指针指到缓存头部，并且设置了最多只能读出之前写入的数据长度（而
        不是整个缓存的容量大小）。
         */
        result.flip();

        return result;
    }

    private byte[] headerEncode() {
        // 将customHeader对象的属性保存到extFields属性
        this.makeCustomHeaderToNet();
        if (SerializeType.ROCKETMQ == serializeTypeCurrentRPC) {
            return RocketMQSerializable.rocketMQProtocolEncode(this);
        } else {
            // 使用JSON进行序列化
            return RemotingSerializable.encode(this);
        }
    }

    public void makeCustomHeaderToNet() {
        // 将customHeader对象的属性保存到extFields属性，用于序列化
        if (this.customHeader != null) {
            Field[] fields = getClazzFields(customHeader.getClass());
            if (null == this.extFields) {
                this.extFields = new HashMap<String, String>();
            }

            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    String name = field.getName();
                    if (!name.startsWith("this")) {
                        Object value = null;
                        try {
                            field.setAccessible(true);
                            value = field.get(this.customHeader);
                        } catch (Exception e) {
                            log.error("Failed to access field [{}]", name, e);
                        }

                        if (value != null) {
                            this.extFields.put(name, value.toString());
                        }
                    }
                }
            }
        }
    }

    public ByteBuffer encodeHeader() {
        return encodeHeader(this.body != null ? this.body.length : 0);
    }

    // 该方法和encode方法的不同在于，body的长度取决于传入的参数，而不是当前RemotingCommand对象自身的body，使得最后返回的字节数组
    // 的第一部分的值带有body的长度，但是返回的字节数组中没有body。该方法在只想要发送请求的header而不想发送请求的消息主体时很有用。
    public ByteBuffer encodeHeader(final int bodyLength) {
        // 1> header length size
        int length = 4;

        // 2> header data length
        byte[] headerData;
        headerData = this.headerEncode();

        length += headerData.length;

        // 3> body data length
        length += bodyLength;

        ByteBuffer result = ByteBuffer.allocate(4 + length - bodyLength);

        // length
        result.putInt(length);

        // header length
        result.put(markProtocolType(headerData.length, serializeTypeCurrentRPC));

        // header data
        result.put(headerData);

        result.flip();

        return result;
    }

    // 标记为oneway类型的请求
    public void markOnewayRPC() {
        int bits = 1 << RPC_ONEWAY;
        this.flag |= bits;
    }

    @JSONField(serialize = false)
    public boolean isOnewayRPC() {
        int bits = 1 << RPC_ONEWAY;
        return (this.flag & bits) == bits;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    @JSONField(serialize = false)
    public RemotingCommandType getType() {
        if (this.isResponseType()) {
            return RemotingCommandType.RESPONSE_COMMAND;
        }

        return RemotingCommandType.REQUEST_COMMAND;
    }

    @JSONField(serialize = false)
    public boolean isResponseType() {
        int bits = 1 << RPC_TYPE;
        return (this.flag & bits) == bits;
    }

    public LanguageCode getLanguage() {
        return language;
    }

    public void setLanguage(LanguageCode language) {
        this.language = language;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getOpaque() {
        return opaque;
    }

    public void setOpaque(int opaque) {
        this.opaque = opaque;
    }

    public int getFlag() {
        return flag;
    }

    public void setFlag(int flag) {
        this.flag = flag;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public HashMap<String, String> getExtFields() {
        return extFields;
    }

    public void setExtFields(HashMap<String, String> extFields) {
        this.extFields = extFields;
    }

    public void addExtField(String key, String value) {
        if (null == extFields) {
            extFields = new HashMap<String, String>();
        }
        extFields.put(key, value);
    }

    @Override
    public String toString() {
        return "RemotingCommand [code=" + code + ", language=" + language + ", version=" + version + ", opaque=" + opaque + ", flag(B)="
            + Integer.toBinaryString(flag) + ", remark=" + remark + ", extFields=" + extFields + ", serializeTypeCurrentRPC="
            + serializeTypeCurrentRPC + "]";
    }

    public SerializeType getSerializeTypeCurrentRPC() {
        return serializeTypeCurrentRPC;
    }

    public void setSerializeTypeCurrentRPC(SerializeType serializeTypeCurrentRPC) {
        this.serializeTypeCurrentRPC = serializeTypeCurrentRPC;
    }
}