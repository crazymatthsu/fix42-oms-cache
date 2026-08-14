package com.fix42.oms.dict;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class GroupDef {
    private final int numInGroupTag;
    private final String name;
    private final int delimiterTag;
    private final Set<Integer> fieldTags;
    private final Map<Integer, GroupDef> nestedByCountTag;

    public GroupDef(
            int numInGroupTag,
            String name,
            int delimiterTag,
            Set<Integer> fieldTags,
            Map<Integer, GroupDef> nestedByCountTag) {
        this.numInGroupTag = numInGroupTag;
        this.name = name;
        this.delimiterTag = delimiterTag;
        this.fieldTags = Collections.unmodifiableSet(new LinkedHashSet<>(fieldTags));
        this.nestedByCountTag = Collections.unmodifiableMap(new LinkedHashMap<>(nestedByCountTag));
    }

    public int numInGroupTag() {
        return numInGroupTag;
    }

    public String name() {
        return name;
    }

    public int delimiterTag() {
        return delimiterTag;
    }

    public Set<Integer> fieldTags() {
        return fieldTags;
    }

    public Map<Integer, GroupDef> nestedByCountTag() {
        return nestedByCountTag;
    }

    public boolean containsField(int tag) {
        return fieldTags.contains(tag);
    }

    public GroupDef nested(int countTag) {
        return nestedByCountTag.get(countTag);
    }
}
