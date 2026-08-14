package com.fix42.oms.fix;

import com.fix42.oms.dict.FixDictionary;
import com.fix42.oms.dict.GroupDef;
import com.fix42.oms.dict.MessageDef;
import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixGroup;
import com.fix42.oms.proto.FixGroupInstance;
import com.fix42.oms.proto.FixHeader;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.FixTrailer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FixParser {
    private final FixDictionary dictionary;
    private final boolean validateChecksum;
    private final boolean strictHeader;

    public FixParser(FixDictionary dictionary) {
        this(dictionary, true, true);
    }

    public FixParser(FixDictionary dictionary, boolean validateChecksum, boolean strictHeader) {
        this.dictionary = dictionary;
        this.validateChecksum = validateChecksum;
        this.strictHeader = strictHeader;
    }

    public static FixParser fix42() {
        return new FixParser(FixDictionary.fix42());
    }

    public FixMessage parse(String raw) {
        String soh = FixSupport.normalizeSoh(raw);
        if (soh.isEmpty()) {
            throw new FixParseException("Empty FIX message");
        }
        if (validateChecksum) {
            validateIntegrity(soh);
        }
        List<FixSupport.Field> fields = FixSupport.splitFields(soh);
        if (fields.isEmpty()) {
            throw new FixParseException("No fields in FIX message");
        }
        if (strictHeader) {
            validateHeaderOrder(fields);
        }

        Cursor cursor = new Cursor(fields);
        FixHeader.Builder header = FixHeader.newBuilder();
        FixTrailer.Builder trailer = FixTrailer.newBuilder();
        List<FixField> body = new ArrayList<>();
        List<FixGroup> groups = new ArrayList<>();

        String msgType = firstValue(fields, Tags.MSG_TYPE);
        MessageDef messageDef = msgType == null ? null : dictionary.message(msgType);
        Map<Integer, GroupDef> topGroups = messageDef == null
                ? Map.of()
                : messageDef.groupsByCountTag();

        while (cursor.hasNext()) {
            FixSupport.Field field = cursor.peek();
            if (field.tag == Tags.BEGIN_STRING) {
                header.setBeginString(cursor.next().value);
            } else if (field.tag == Tags.BODY_LENGTH) {
                header.setBodyLength(parseInt(cursor.next().value, "BodyLength"));
            } else if (field.tag == Tags.MSG_TYPE) {
                header.setMsgType(cursor.next().value);
            } else if (field.tag == Tags.SENDER_COMP_ID) {
                header.setSenderCompId(cursor.next().value);
            } else if (field.tag == Tags.TARGET_COMP_ID) {
                header.setTargetCompId(cursor.next().value);
            } else if (field.tag == Tags.MSG_SEQ_NUM) {
                header.setMsgSeqNum(parseInt(cursor.next().value, "MsgSeqNum"));
            } else if (field.tag == Tags.SENDING_TIME) {
                header.setSendingTime(cursor.next().value);
            } else if (field.tag == Tags.CHECK_SUM) {
                trailer.setCheckSum(cursor.next().value);
            } else if (FixConstants.HEADER_TAGS.contains(field.tag)) {
                FixSupport.Field extra = cursor.next();
                header.addExtra(field(extra));
            } else if (FixConstants.TRAILER_TAGS.contains(field.tag)) {
                cursor.next();
            } else {
                GroupDef groupDef = topGroups.get(field.tag);
                if (groupDef == null && dictionary.isNumInGroup(field.tag)) {
                    groupDef = dictionary.group(field.tag);
                }
                if (groupDef != null) {
                    groups.add(parseGroup(cursor, groupDef));
                } else {
                    body.add(field(cursor.next()));
                }
            }
        }

        return FixMessage.newBuilder()
                .setHeader(header)
                .addAllFields(body)
                .addAllGroups(groups)
                .setTrailer(trailer)
                .setRaw(raw)
                .build();
    }

    private FixGroup parseGroup(Cursor cursor, GroupDef def) {
        FixSupport.Field countField = cursor.next();
        int declared;
        try {
            declared = Integer.parseInt(countField.value);
        } catch (NumberFormatException e) {
            throw new FixParseException("Invalid NumInGroup " + def.numInGroupTag() + "=" + countField.value, e);
        }
        if (declared < 0) {
            throw new FixParseException("Negative NumInGroup " + def.numInGroupTag());
        }
        List<FixGroupInstance.Builder> instances = new ArrayList<>();
        FixGroupInstance.Builder current = null;
        while (cursor.hasNext()) {
            FixSupport.Field peek = cursor.peek();
            GroupDef nested = def.nested(peek.tag);
            if (nested != null) {
                if (current == null) {
                    if (instances.size() >= declared) {
                        break;
                    }
                    current = FixGroupInstance.newBuilder();
                    instances.add(current);
                }
                current.addNestedGroups(parseGroup(cursor, nested));
                continue;
            }
            if (def.containsField(peek.tag)) {
                if (peek.tag == def.delimiterTag() || current == null) {
                    if (instances.size() >= declared) {
                        break;
                    }
                    current = FixGroupInstance.newBuilder();
                    instances.add(current);
                }
                current.addFields(field(cursor.next()));
            } else {
                break;
            }
        }
        FixGroup.Builder group = FixGroup.newBuilder().setNumInGroupTag(def.numInGroupTag());
        for (FixGroupInstance.Builder instance : instances) {
            group.addInstances(instance);
        }
        return group.build();
    }

    private static void validateHeaderOrder(List<FixSupport.Field> fields) {
        if (fields.size() < 4) {
            throw new FixParseException("FIX message too short");
        }
        if (fields.get(0).tag != Tags.BEGIN_STRING) {
            throw new FixParseException("First field must be 8=BeginString");
        }
        if (fields.get(1).tag != Tags.BODY_LENGTH) {
            throw new FixParseException("Second field must be 9=BodyLength");
        }
        if (fields.get(2).tag != Tags.MSG_TYPE) {
            throw new FixParseException("Third field must be 35=MsgType");
        }
        FixSupport.Field last = fields.get(fields.size() - 1);
        if (last.tag != Tags.CHECK_SUM) {
            throw new FixParseException("Last field must be 10=CheckSum");
        }
    }

    private static void validateIntegrity(String soh) {
        byte[] bytes = soh.getBytes(StandardCharsets.US_ASCII);
        int checksumAt = FixSupport.indexOf(bytes, "10=");
        if (checksumAt < 0) {
            throw new FixParseException("Missing CheckSum (10)");
        }
        int expected = FixSupport.checksum(bytes, checksumAt);
        int eq = checksumAt + 3;
        int end = eq;
        while (end < bytes.length && bytes[end] != FixConstants.SOH) {
            end++;
        }
        String actual = new String(bytes, eq, end - eq, StandardCharsets.US_ASCII);
        String expected3 = FixSupport.checksum3(expected);
        if (!expected3.equals(actual)) {
            throw new FixParseException("CheckSum mismatch: expected " + expected3 + " got " + actual);
        }
        int bodyStart = FixSupport.indexOf(bytes, "35=");
        if (bodyStart < 0) {
            throw new FixParseException("Missing MsgType (35)");
        }
        int declared;
        try {
            int nineEq = FixSupport.indexOf(bytes, "9=");
            if (nineEq < 0) {
                throw new FixParseException("Missing BodyLength (9)");
            }
            int nineValStart = nineEq + 2;
            int nineValEnd = nineValStart;
            while (nineValEnd < bytes.length && bytes[nineValEnd] != FixConstants.SOH) {
                nineValEnd++;
            }
            declared = Integer.parseInt(new String(bytes, nineValStart, nineValEnd - nineValStart, StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            throw new FixParseException("Invalid BodyLength", e);
        }
        int actualLen = checksumAt - bodyStart;
        if (declared != actualLen) {
            throw new FixParseException("BodyLength mismatch: expected " + declared + " got " + actualLen);
        }
    }

    private static String firstValue(List<FixSupport.Field> fields, int tag) {
        for (FixSupport.Field field : fields) {
            if (field.tag == tag) {
                return field.value;
            }
        }
        return null;
    }

    private static FixField field(FixSupport.Field field) {
        return FixField.newBuilder().setTag(field.tag).setValue(field.value).build();
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new FixParseException("Invalid integer for " + name + ": " + value, e);
        }
    }

    private static final class Cursor {
        private final List<FixSupport.Field> fields;
        private int index;

        private Cursor(List<FixSupport.Field> fields) {
            this.fields = fields;
        }

        boolean hasNext() {
            return index < fields.size();
        }

        FixSupport.Field peek() {
            return fields.get(index);
        }

        FixSupport.Field next() {
            return fields.get(index++);
        }
    }
}
