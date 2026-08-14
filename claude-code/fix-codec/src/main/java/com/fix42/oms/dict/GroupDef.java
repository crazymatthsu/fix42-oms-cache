package com.fix42.oms.dict;

import java.util.List;

/**
 * Definition of a FIX repeating group.
 *
 * @param countTag     the NoXXX field whose integer value is the number of entries
 * @param delimiterTag the first (delimiting) member tag that starts each entry
 * @param memberTags   all tags that may appear in an entry, in canonical order
 *                     (includes {@code delimiterTag} as the first element)
 */
public record GroupDef(int countTag, int delimiterTag, List<Integer> memberTags) {

    public GroupDef {
        memberTags = List.copyOf(memberTags);
        if (!memberTags.isEmpty() && memberTags.get(0) != delimiterTag) {
            throw new IllegalArgumentException(
                    "delimiterTag " + delimiterTag + " must be the first member tag of group " + countTag);
        }
    }

    public boolean isMember(int tag) {
        return memberTags.contains(tag);
    }
}
