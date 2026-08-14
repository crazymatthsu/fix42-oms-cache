package com.fix42.oms.dict;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Definition of a FIX message type: which body fields it may carry and its repeating
 * groups. Header/trailer fields are common to all messages and tracked separately by
 * {@link FixDictionary}.
 */
public final class MessageDef {

    private final String msgType;
    private final String name;
    private final Set<Integer> fieldTags;      // body fields (order-preserving)
    private final List<GroupDef> groups;

    public MessageDef(String msgType, String name, Set<Integer> fieldTags, List<GroupDef> groups) {
        this.msgType = msgType;
        this.name = name;
        this.fieldTags = new LinkedHashSet<>(fieldTags);
        this.groups = List.copyOf(groups);
    }

    public String msgType() {
        return msgType;
    }

    public String name() {
        return name;
    }

    public Set<Integer> fieldTags() {
        return fieldTags;
    }

    public List<GroupDef> groups() {
        return groups;
    }

    public boolean hasField(int tag) {
        return fieldTags.contains(tag);
    }

    /** The group introduced by {@code countTag}, if any. */
    public Optional<GroupDef> groupByCountTag(int countTag) {
        for (GroupDef g : groups) {
            if (g.countTag() == countTag) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }

    /** The group whose count tag this is, resolved from a member (delimiter) tag. */
    public Optional<GroupDef> groupByDelimiterTag(int delimiterTag) {
        for (GroupDef g : groups) {
            if (g.delimiterTag() == delimiterTag) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }
}
