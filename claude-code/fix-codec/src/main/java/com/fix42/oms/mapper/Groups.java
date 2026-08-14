package com.fix42.oms.mapper;

import com.fix42.oms.dict.GroupDef;
import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts flat repeating-group entries from a generic {@link FixMessage} using a
 * {@link GroupDef}. Handles the FIX 4.2 groups in scope (NoAllocs, NoContraBrokers),
 * which are non-nested: a NoXXX count tag followed by N entries, each begun by the
 * group's delimiter tag.
 */
public final class Groups {

    private Groups() {
    }

    /**
     * @return one entry per group repetition, each a list of the entry's fields in
     * message order; empty if the count tag is absent.
     */
    public static List<List<FixField>> extract(FixMessage msg, GroupDef def) {
        List<FixField> fields = msg.getFieldsList();
        List<List<FixField>> entries = new ArrayList<>();

        int i = 0;
        // Advance to the first field after the count tag.
        while (i < fields.size() && fields.get(i).getTag() != def.countTag()) {
            i++;
        }
        if (i >= fields.size()) {
            return entries; // no such group
        }
        i++; // skip the count field itself

        List<FixField> current = null;
        for (; i < fields.size(); i++) {
            FixField field = fields.get(i);
            int tag = field.getTag();
            if (tag == def.delimiterTag()) {
                if (current != null) {
                    entries.add(current);
                }
                current = new ArrayList<>();
                current.add(field);
            } else if (def.isMember(tag) && current != null) {
                current.add(field);
            } else {
                break; // first non-member tag ends the group
            }
        }
        if (current != null) {
            entries.add(current);
        }
        return entries;
    }

    /** Find the first value of {@code tag} within an entry's fields, or {@code null}. */
    public static String value(List<FixField> entry, int tag) {
        for (FixField f : entry) {
            if (f.getTag() == tag) {
                return f.getValue();
            }
        }
        return null;
    }
}
