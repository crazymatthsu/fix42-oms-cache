package com.fix42.oms.dict;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MessageDef {
    private final String msgType;
    private final String name;
    private final Map<Integer, GroupDef> groupsByCountTag;

    public MessageDef(String msgType, String name, Map<Integer, GroupDef> groupsByCountTag) {
        this.msgType = msgType;
        this.name = name;
        this.groupsByCountTag = Collections.unmodifiableMap(new LinkedHashMap<>(groupsByCountTag));
    }

    public String msgType() {
        return msgType;
    }

    public String name() {
        return name;
    }

    public Map<Integer, GroupDef> groupsByCountTag() {
        return groupsByCountTag;
    }

    public GroupDef group(int countTag) {
        return groupsByCountTag.get(countTag);
    }
}
